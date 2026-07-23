package io.github.hhagenbuch.conductor.flock;

import io.github.hhagenbuch.conductor.daemon.Registry;
import io.github.hhagenbuch.conductor.transcript.Consent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The three-way AND, end to end: a flock alert fires only when a session's
/// pending change touches a contract surface (1) with a cross-repo consumer (2)
/// that has a live, consenting session (3), and the change is graded BREAKING.
/// Drives the real registry, consent gating, transcript digest, and classifier;
/// only the fathom graph is faked.
class FlockEngineTest {

    /// A scriptable stand-in for the fathom graph.
    private static final class FakeFathom implements FathomGraph {
        Map<String, List<String>> files = Map.of();     // "repo/path" -> entity ids
        Map<String, Impact> impacts = Map.of();          // entity id -> impact
        boolean reachable = true;

        @Override public Entities resolveFile(String repo, String path) {
            return new Entities(files.getOrDefault(repo + "/" + path, List.of()));
        }
        @Override public Impact impactedBy(String entityId) {
            return impacts.getOrDefault(entityId, Impact.EMPTY);
        }
        @Override public boolean reachable() {
            return reachable;
        }
        @Override public void close() { }
    }

    private static final String DTO_V1 = "public record OrderRequest(String sku, int qty) {}";
    private static final String DTO_BREAKING = "public record OrderRequest(String sku, int qty, String promoCode) {}";
    private static final String DTO_ADDITIVE = "public record OrderRequest(String sku, int qty, Optional<String> promo) {}";

    private static void git(Path dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args));
        }
    }

    private Registry registry(Path tmp) throws Exception {
        return new Registry(tmp.resolve("bus-" + UUID.randomUUID() + ".db"), Clock.systemUTC());
    }

    /// A source repo whose session has a pending change to `relFile` and a
    /// consented transcript that names it. Returns the session id.
    private String source(Path repoDir, String relFile, String pending, Registry reg) throws Exception {
        Files.createDirectories(repoDir);
        git(repoDir, "init", "-q");
        git(repoDir, "config", "user.email", "t@t");
        git(repoDir, "config", "user.name", "t");
        var file = repoDir.resolve(relFile);
        Files.createDirectories(file.getParent());
        Files.writeString(file, DTO_V1);
        git(repoDir, "add", ".");
        git(repoDir, "commit", "-q", "-m", "base");
        Files.writeString(file, pending); // pending working-tree change

        var transcript = repoDir.resolve("t.jsonl");
        Files.writeString(transcript,
                "{\"type\":\"assistant\",\"timestamp\":\"2026-07-23T00:00:00Z\",\"message\":{\"content\":"
                + "[{\"type\":\"tool_use\",\"name\":\"Edit\",\"input\":{\"file_path\":\""
                + file.toAbsolutePath() + "\"}}]}}\n");
        Consent.grant(repoDir, "flock test");

        var id = UUID.randomUUID().toString();
        reg.register(id, repoDir.toString(), "main", repoDir.toString(), transcript.toString(), false);
        return id;
    }

    /// A live consumer session in `repoDir` (basename = the fathom repo name),
    /// consenting so it may receive alerts.
    private String consumer(Path repoDir, Registry reg, boolean consent) throws Exception {
        Files.createDirectories(repoDir);
        git(repoDir, "init", "-q");
        if (consent) {
            Consent.grant(repoDir, "flock test");
        }
        var id = UUID.randomUUID().toString();
        reg.register(id, repoDir.toString(), "main", repoDir.toString(), null, false);
        return id;
    }

    private FakeFathom breakingSurfaceConsumedBy(String consumerRepo) {
        var fake = new FakeFathom();
        fake.files = Map.of("service-a/src/OrderRequest.java", List.of("Symbol:OrderRequest"));
        fake.impacts = Map.of("Symbol:OrderRequest", new FathomGraph.Impact(
                "Symbol:OrderRequest", true, "api-dto",
                List.of(new FathomGraph.Consumer(consumerRepo, "Symbol:Checkout", "references", true))));
        return fake;
    }

    private FlockConfig on(boolean additive) {
        return new FlockConfig(true, List.of("fathom", "serve"), 10, additive);
    }

    private long inboxCount(Registry reg, String sessionId) throws Exception {
        return reg.inbox(sessionId, false).size();
    }

    // ---- the three-way AND ----

    @Test
    void breakingSurfaceChangeAlertsLiveConsumer(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_BREAKING, reg);
        var dst = consumer(tmp.resolve("service-b"), reg, true);
        var engine = new FlockEngine(reg, breakingSurfaceConsumedBy("service-b"), on(false));

        var outcome = engine.evaluate(src);

        assertEquals(1, outcome.posted(), "one alert to the live consumer");
        var msgs = reg.inbox(dst, false);
        assertEquals(1, msgs.size());
        assertTrue(msgs.getFirst().body().contains("FLOCK"), msgs.getFirst().body());
        assertTrue(msgs.getFirst().body().contains("OrderRequest"));
        engine.close();
    }

    @Test
    void notASurfaceIsSilent(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_BREAKING, reg);
        consumer(tmp.resolve("service-b"), reg, true);
        var fake = breakingSurfaceConsumedBy("service-b");
        // flip: the changed entity is NOT a contract surface
        fake.impacts = Map.of("Symbol:OrderRequest",
                new FathomGraph.Impact("Symbol:OrderRequest", false, null,
                        List.of(new FathomGraph.Consumer("service-b", "Symbol:Checkout", "references", false))));
        var engine = new FlockEngine(reg, fake, on(false));

        assertEquals(0, engine.evaluate(src).posted(), "internal-surface: no consumer edge is surface-crossing");
        engine.close();
    }

    @Test
    void noLiveConsumerSessionIsSilent(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_BREAKING, reg);
        // no session registered in service-b at all
        var engine = new FlockEngine(reg, breakingSurfaceConsumedBy("service-b"), on(false));

        assertEquals(0, engine.evaluate(src).posted(), "no live session in the consuming repo");
        engine.close();
    }

    @Test
    void internalOnlyShapeChangeIsSilent(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        // pending change is identical shape (body-only): classifier grades INTERNAL
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java",
                DTO_V1 + "\n// touched, shape unchanged", reg);
        var dst = consumer(tmp.resolve("service-b"), reg, true);
        var engine = new FlockEngine(reg, breakingSurfaceConsumedBy("service-b"), on(false));

        assertEquals(0, engine.evaluate(src).posted(), "shape did not change → silence");
        assertEquals(0, inboxCount(reg, dst));
        engine.close();
    }

    @Test
    void additiveChangeIsOffByDefaultAndOnWithFlag(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_ADDITIVE, reg);
        var dst = consumer(tmp.resolve("service-b"), reg, true);

        var off = new FlockEngine(reg, breakingSurfaceConsumedBy("service-b"), on(false));
        assertEquals(0, off.evaluate(src).posted(), "COMPAT is silent by default");
        off.close();

        var on = new FlockEngine(reg, breakingSurfaceConsumedBy("service-b"), on(true));
        assertEquals(1, on.evaluate(src).posted(), "COMPAT alerts with --flock-additive");
        on.close();
    }

    // ---- noise control ----

    @Test
    void throttleSuppressesRepeatAlerts(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_BREAKING, reg);
        var dst = consumer(tmp.resolve("service-b"), reg, true);
        var engine = new FlockEngine(reg, breakingSurfaceConsumedBy("service-b"), on(false));

        assertEquals(1, engine.evaluate(src).posted(), "first fires");
        assertEquals(0, engine.evaluate(src).posted(), "second within window is throttled");
        assertEquals(1, inboxCount(reg, dst), "consumer got exactly one");
        engine.close();
    }

    @Test
    void snoozeSuppressesTheEntity(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_BREAKING, reg);
        var dst = consumer(tmp.resolve("service-b"), reg, true);
        reg.flockSnooze(dst, "Symbol:OrderRequest", System.currentTimeMillis() + 3_600_000);
        var engine = new FlockEngine(reg, breakingSurfaceConsumedBy("service-b"), on(false));

        assertEquals(0, engine.evaluate(src).posted(), "snoozed entity is muted for that session");
        engine.close();
    }

    @Test
    void consumerWithoutConsentIsNotTold(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_BREAKING, reg);
        consumer(tmp.resolve("service-b"), reg, false); // no consent on the consumer side
        var engine = new FlockEngine(reg, breakingSurfaceConsumedBy("service-b"), on(false));

        assertEquals(0, engine.evaluate(src).posted(), "both-sides consent: B is told only if B consents");
        engine.close();
    }

    @Test
    void sourceWithoutConsentContributesNoFiles(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_BREAKING, reg);
        Consent.revoke(tmp.resolve("service-a")); // withdraw source consent
        consumer(tmp.resolve("service-b"), reg, true);
        var engine = new FlockEngine(reg, breakingSurfaceConsumedBy("service-b"), on(false));

        assertEquals(0, engine.evaluate(src).posted(), "unconsented source: its file set is not inspected");
        engine.close();
    }

    // ---- degrade & authority ----

    @Test
    void unreachableFathomDegradesToSilenceNotError(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_BREAKING, reg);
        consumer(tmp.resolve("service-b"), reg, true);
        var fake = new FakeFathom();
        fake.reachable = false; // graph unreachable: resolveFile/impactedBy return empty
        var engine = new FlockEngine(reg, fake, on(false));

        assertEquals(0, engine.evaluate(src).posted(), "no graph → no alerts, no throw");
        engine.close();
    }

    @Test
    void engineNeverBlocksItOnlyPosts(@TempDir Path tmp) throws Exception {
        // Authority doctrine: the engine's only side effect is a posted message.
        // A leaseless registry and an evaluation that posts proves there is no
        // deny/block path reachable from Flock (it has no Enforcer dependency).
        var reg = registry(tmp);
        var src = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_BREAKING, reg);
        var dst = consumer(tmp.resolve("service-b"), reg, true);
        var engine = new FlockEngine(reg, breakingSurfaceConsumedBy("service-b"), on(false));

        engine.evaluate(src);
        assertTrue(reg.allActiveLeases().isEmpty(), "Flock claims no leases and blocks nothing");
        assertFalse(reg.inbox(dst, false).isEmpty(), "its sole effect is an advisory post");
        engine.close();
    }

    // ---- bidirectional from one code path ----

    @Test
    void bidirectionalBothDirectionsFireFromOneCodePath(@TempDir Path tmp) throws Exception {
        var reg = registry(tmp);
        // A and B each change a surface the OTHER consumes.
        var a = source(tmp.resolve("service-a"), "src/OrderRequest.java", DTO_BREAKING, reg);
        var b = source(tmp.resolve("service-b"), "src/OrderRequest.java", DTO_BREAKING, reg);

        var fake = new FakeFathom();
        fake.files = Map.of(
                "service-a/src/OrderRequest.java", List.of("Symbol:A"),
                "service-b/src/OrderRequest.java", List.of("Symbol:B"));
        fake.impacts = Map.of(
                "Symbol:A", new FathomGraph.Impact("Symbol:A", true, "api-dto",
                        List.of(new FathomGraph.Consumer("service-b", "Symbol:B", "references", true))),
                "Symbol:B", new FathomGraph.Impact("Symbol:B", true, "api-dto",
                        List.of(new FathomGraph.Consumer("service-a", "Symbol:A", "references", true))));
        var engine = new FlockEngine(reg, fake, on(false));

        // The SAME evaluate() path, run per source session.
        assertEquals(1, engine.evaluate(a).posted(), "A→B");
        assertEquals(1, engine.evaluate(b).posted(), "B→A");
        assertEquals(1, inboxCount(reg, a), "A was told about B's change");
        assertEquals(1, inboxCount(reg, b), "B was told about A's change");
        engine.close();
    }
}
