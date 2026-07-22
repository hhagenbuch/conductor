package io.github.hhagenbuch.conductor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.conductor.daemon.Daemon;
import io.github.hhagenbuch.conductor.daemon.Registry;
import io.github.hhagenbuch.conductor.shim.DaemonClient;
import io.github.hhagenbuch.conductor.transcript.Consent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/// Phase 3 acceptance: a briefing bundle is produced from a real (consented)
/// session, and consent gates whether the transcript is read at all.
class BriefingE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void briefingIsRegistryOnlyWithoutConsentThenRichWithIt(@TempDir Path tmp) throws Exception {
        var home = ConductorHome.of(tmp.resolve(".conductor"));
        var project = tmp.resolve("proj");
        Files.createDirectories(project);

        // A synthetic transcript on disk (copied from the test fixture).
        var transcript = project.resolve("session.jsonl");
        Files.copy(Path.of("src/test/resources/fixtures/synthetic-session.jsonl"), transcript);

        try (var registry = new Registry(home.db(), Clock.systemUTC())) {
            var server = Daemon.startServer(registry, new InetSocketAddress("127.0.0.1", 0));
            int port = server.getAddress().getPort();
            Files.writeString(home.portFile(), Integer.toString(port));
            try {
                var client = new DaemonClient(home, Duration.ofSeconds(2));
                // Register a session whose transcript_path points at our fixture,
                // and whose project/worktree is `project`.
                registry.register("worker-1", project.toString(), "feature/retry",
                        project.toString(), transcript.toString(), false);

                // No consent yet -> registry-only briefing, transcript untouched.
                var before = brief(client, port, "worker-1");
                assertFalse(before.path("consented").asBoolean());
                assertTrue(before.path("briefing").asText().contains("NOT consented"));
                assertFalse(registry.session("worker-1").orElseThrow().observed(),
                        "no observation without consent");

                // Grant consent -> the digest appears and the session is observed.
                Consent.grant(project, "phase-3 test");
                var after = brief(client, port, "worker-1");
                assertTrue(after.path("consented").asBoolean());
                var bundle = after.path("briefing").asText();
                assertTrue(bundle.contains("retry"), "task from the transcript: " + bundle);
                assertTrue(bundle.contains("files touched"), bundle);
                assertTrue(bundle.contains("HttpClient.java"), bundle);
                assertTrue(bundle.contains("re-brief") || bundle.contains("brief_me"),
                        "briefing must warn it is a snapshot");
                assertTrue(registry.session("worker-1").orElseThrow().observed(),
                        "tailing a consented session marks it observed");
            } finally {
                server.stop(0);
            }
        }
    }

    private JsonNode brief(DaemonClient client, int port, String session) throws Exception {
        return JSON.readTree(client.get(port, "/api/brief?session=" + session).orElseThrow());
    }
}
