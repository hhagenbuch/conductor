package io.github.hhagenbuch.conductor.flock;

import io.github.hhagenbuch.conductor.daemon.Registry;
import io.github.hhagenbuch.conductor.transcript.Briefings;
import io.github.hhagenbuch.conductor.transcript.Consent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Impact awareness (Flock). When a session's pending change touches a contract
/// surface that another live session consumes, this posts that session an
/// advisory alert ... before the break lands. It is the layer above collision
/// leases: not "don't edit the same file" but "your change reaches my service."
///
/// The pipeline runs for ONE source session per evaluation and is stateless
/// (throttle/snooze state lives in the registry). It fires only at the
/// intersection of the three conditions that separate signal from noise:
///
///   1. the source's pending change touches a contract-surface entity (fathom),
///   2. that entity has a cross-repo consumer edge (fathom `impacted_by`), and
///   3. a live session is working in the consuming repo (registry).
///
/// ...and only when the change is graded BREAKING (or COMPAT with additive
/// alerts enabled). Any condition missing → silence. It never writes to a
/// working tree and never blocks: its only output is a `post` to an inbox. Every
/// fathom call is fail-open, so an unreachable graph disables impact awareness
/// silently while leases and messaging keep working.
public final class FlockEngine implements AutoCloseable {

    /// What one evaluation did, for logging/testing. `posted` is the count of
    /// alerts actually delivered; `considered` is candidate (surface, consumer)
    /// pairs examined.
    public record Outcome(int considered, int posted, List<String> alerts) {
        static final Outcome NONE = new Outcome(0, 0, List.of());
    }

    private final Registry registry;
    private final FathomGraph fathom;
    private final FlockConfig config;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "flock-engine");
        t.setDaemon(true);
        return t;
    });

    public FlockEngine(Registry registry, FathomGraph fathom, FlockConfig config) {
        this.registry = registry;
        this.fathom = fathom;
        this.config = config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /// Whether the fathom graph is currently reachable, so `ps`/`who_else` can
    /// tell a "no impact" silence apart from a "not watching" one.
    public boolean fathomReachable() {
        return config.runnable() && fathom.reachable();
    }

    /// Fire an evaluation for `sourceSessionId` off the hook path, so the hook
    /// returns immediately and the bus never blocks on a graph query. Evaluations
    /// are serialized on one worker thread; a slow fathom cannot pile up work.
    public void triggerAsync(String sourceSessionId) {
        if (!config.runnable()) {
            return;
        }
        try {
            worker.submit(() -> evaluate(sourceSessionId));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            // engine closing; drop the evaluation
        }
    }

    /// Evaluate the pending changes of `sourceSessionId` and post any warranted
    /// flock alerts. Never throws ... a failure anywhere degrades to "no alert".
    public Outcome evaluate(String sourceSessionId) {
        try {
            return evaluateOrThrow(sourceSessionId);
        } catch (Exception degrade) {
            return Outcome.NONE;
        }
    }

    private Outcome evaluateOrThrow(String sourceSessionId) throws Exception {
        var sourceOpt = registry.session(sourceSessionId);
        if (sourceOpt.isEmpty() || !sourceOpt.get().status().equals("active")) {
            return Outcome.NONE;
        }
        var source = sourceOpt.get();

        // Condition source-side: A's changes are inspected only with A's project
        // consent. changedFiles is already consent-gated (empty without it).
        var changed = Briefings.changedFiles(source);
        if (changed.isEmpty()) {
            return Outcome.NONE;
        }

        var repoRoot = repoRoot(source);
        var repoName = repoName(repoRoot);
        var live = registry.liveSessions();
        var classifier = new ChangeClassifier(repoRoot);

        int considered = 0;
        int posted = 0;
        var alerts = new ArrayList<String>();

        for (var absOrRel : changed) {
            var relPath = toRepoRelative(repoRoot, absOrRel);
            if (relPath == null) {
                continue;
            }
            var entities = fathom.resolveFile(repoName, relPath).ids();
            for (var entityId : entities) {
                var impact = fathom.impactedBy(entityId);
                // (1) must be a contract surface; (2) must have cross-repo consumers.
                if (!impact.surface() || impact.consumers().isEmpty()) {
                    continue;
                }
                // grade the pending shape delta against the surface
                var verdict = classifier.classify(relPath, impact.surfaceKind());
                if (!alerts_worthy(verdict.grade())) {
                    continue;
                }
                for (var consumer : impact.consumers()) {
                    // (3) a live session working the consuming repo
                    for (var target : live) {
                        if (target.sessionId().equals(sourceSessionId)) {
                            continue;
                        }
                        if (!repoName(repoRoot(target)).equalsIgnoreCase(consumer.repo())) {
                            continue;
                        }
                        considered++;
                        if (postAlert(source, target, repoName, entityId, consumer, verdict)) {
                            posted++;
                            alerts.add(entityId + " → " + shortId(target.sessionId()));
                        }
                    }
                }
            }
        }
        return new Outcome(considered, posted, alerts);
    }

    private boolean alerts_worthy(ChangeClassifier.Grade grade) {
        return grade == ChangeClassifier.Grade.BREAKING
                || (grade == ChangeClassifier.Grade.COMPAT && config.additive());
    }

    private boolean postAlert(Registry.Session source, Registry.Session target, String sourceRepo,
                              String entityId, FathomGraph.Consumer consumer,
                              ChangeClassifier.Result verdict) throws Exception {
        // Consumer-side consent: B is told only if B's project consents.
        if (!Consent.isGranted(repoRoot(target))) {
            return false;
        }
        // Snooze: B has muted this entity.
        if (registry.flockSnoozed(target.sessionId(), entityId)) {
            return false;
        }
        // Throttle: at most one alert per (source, entity, consumer) per window.
        if (!registry.flockAllowAndRecord(source.sessionId(), entityId, target.sessionId(),
                config.throttleMillis())) {
            return false;
        }
        var body = compose(source, sourceRepo, entityId, consumer, verdict);
        return registry.post(source.sessionId(), target.sessionId(), body).isPresent();
    }

    private String compose(Registry.Session source, String sourceRepo, String entityId,
                           FathomGraph.Consumer consumer, ChangeClassifier.Result verdict) {
        var task = source.statedTask() != null && !source.statedTask().isBlank()
                ? source.statedTask() : "(no stated task)";
        var verb = verdict.grade() == ChangeClassifier.Grade.BREAKING ? "a breaking change" : "an additive change";
        return "⚠ FLOCK: session " + shortId(source.sessionId()) + " is making " + verb
                + " to " + sourceRepo + " " + entityId + " (a contract " + consumer.repo()
                + " consumes via " + consumer.edgeKind() + ") — " + verdict.detail() + ".\n"
                + "You are live on " + consumer.repo() + ", which depends on it. Coordinate before "
                + "relying on the current shape.\n"
                + "[entity: " + entityId + "] [change: " + verdict.grade() + "] [holder: "
                + shortId(source.sessionId()) + ", task: " + task + "]\n"
                + "(edges are from fathom's graph and may lag the working tree; the shape delta above "
                + "is read live. Mute with the `snooze` tool.)";
    }

    // ---- repo identity helpers ----

    private static Path repoRoot(Registry.Session s) {
        return s.worktree() != null ? Path.of(s.worktree()) : Path.of(s.projectDir());
    }

    /// fathom indexes a repo under a short name; we use the repo root's basename,
    /// which is how these repos are named in a fathom.yaml.
    private static String repoName(Path repoRoot) {
        var name = repoRoot.getFileName();
        return name == null ? repoRoot.toString() : name.toString();
    }

    /// Convert a touched file (which may be absolute or already repo-relative)
    /// into a path relative to the repo root. Null if it lies outside the repo.
    private static String toRepoRelative(Path repoRoot, String touched) {
        var p = Path.of(touched);
        if (!p.isAbsolute()) {
            return touched.replace('\\', '/');
        }
        var root = repoRoot.toAbsolutePath().normalize();
        var abs = p.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) {
            return null;
        }
        return root.relativize(abs).toString().replace('\\', '/');
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.length() <= 8 ? id : id.substring(0, 8);
    }

    @Override
    public void close() {
        worker.shutdownNow();
        fathom.close();
    }
}
