package io.github.hhagenbuch.conductor.assist;

import io.github.hhagenbuch.conductor.daemon.Registry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AssistSpawnerTest {

    /// A worktree that just makes/removes a real directory, tracking removals.
    private static final class FakeWorktrees implements Worktrees, Worktrees.Factory {
        final java.util.List<Path> removed = new java.util.ArrayList<>();
        boolean failAdd = false;

        @Override public Worktrees forRepo(Path parentRepo) { return this; }

        @Override public Path add(Path where, String branch) throws Exception {
            if (failAdd) {
                throw new Exception("worktree add failed");
            }
            Files.createDirectories(where);
            return where;
        }

        @Override public void remove(Path where) { removed.add(where); }

        @Override public void removeQuietly(Path where) { removed.add(where); }
    }

    private Registry registry(Path dir) throws Exception {
        return new Registry(dir.resolve("t.db"), Clock.systemUTC());
    }

    @Test
    void happyPathRegistersClaimsBriefsAndNotifiesParent(@TempDir Path tmp) throws Exception {
        var repo = tmp.resolve("proj");
        Files.createDirectories(repo);
        try (var reg = registry(tmp)) {
            reg.register("parent-1", repo.toString(), "main", repo.toString(), null, false);
            var wt = new FakeWorktrees();
            var launched = new java.util.concurrent.atomic.AtomicReference<AssistSpawner.Spec>();
            AssistSpawner.Launcher launcher = spec -> { launched.set(spec); return new AssistSpawner.Handle(4242); };

            var spawner = new AssistSpawner(reg, wt, launcher, () -> "helper-abcdef01");
            var result = spawner.assist("parent-1", "take the test suite",
                    List.of("path:src/test/**"), List.of("Read", "Edit", "Bash"));

            assertEquals("helper-abcdef01", result.helperId());
            assertEquals("feature/assist-helper-a", result.branch());
            assertEquals(4242, result.pid());

            // Helper is registered, as a child, with its lease.
            var helper = reg.session("helper-abcdef01").orElseThrow();
            assertTrue(helper.isChild());
            assertEquals(1, reg.activeLeases(repo.toString()).stream()
                    .filter(l -> l.sessionId().equals("helper-abcdef01")).count());

            // Briefing file written into the worktree, mentioning the assignment.
            assertTrue(Files.exists(result.briefingFile()));
            assertTrue(Files.readString(result.briefingFile()).contains("take the test suite"));

            // Launcher saw scoped allowedTools (no blanket bypass).
            assertEquals(List.of("Read", "Edit", "Bash"), launched.get().allowedTools());

            // Parent got a message naming the helper.
            var inbox = reg.inbox("parent-1", true);
            assertEquals(1, inbox.size());
            assertTrue(inbox.getFirst().body().contains("helper-a"));
            assertTrue(inbox.getFirst().body().contains("take the test suite"));
        }
    }

    @Test
    void spawnFailureLeavesNoPhantomAndNoOrphanLease(@TempDir Path tmp) throws Exception {
        var repo = tmp.resolve("proj");
        Files.createDirectories(repo);
        try (var reg = registry(tmp)) {
            reg.register("parent-1", repo.toString(), "main", repo.toString(), null, false);
            var wt = new FakeWorktrees();
            // Launcher throws AFTER the helper is registered and has claimed leases.
            AssistSpawner.Launcher boom = spec -> { throw new Exception("claude failed to start"); };

            var spawner = new AssistSpawner(reg, wt, boom, () -> "helper-dead0001");
            var ex = assertThrows(Exception.class, () -> spawner.assist("parent-1", "doomed slice",
                    List.of("repo:"), List.of("Read")));
            assertTrue(ex.getMessage().contains("failed to start"));

            // The phantom is cleaned: session ended, and — the key property —
            // it holds NO leases, so `ps` shows no orphan lease on a dead helper.
            assertTrue(reg.activeLeases(repo.toString()).isEmpty(),
                    "a failed spawn must not leave the helper's leases behind");
            var helper = reg.session("helper-dead0001").orElseThrow();
            assertEquals("ended", helper.status(), "phantom helper is marked ended");
            assertFalse(wt.removed.isEmpty(), "the worktree is removed on failure");
        }
    }

    @Test
    void mintsAFreshIdEveryCallNeverReusing(@TempDir Path tmp) throws Exception {
        // A used --session-id can never be reclaimed (GROUND-TRUTH §3), so
        // assist must mint a fresh id per call. Prove the spawner pulls a new
        // id from its source each time rather than reusing one.
        var repo = tmp.resolve("proj");
        Files.createDirectories(repo);
        try (var reg = registry(tmp)) {
            reg.register("parent-1", repo.toString(), "main", repo.toString(), null, false);
            var ids = new ArrayDeque<>(List.of("helper-00000001", "helper-00000002"));
            var counter = new AtomicInteger();
            AssistSpawner.Launcher launcher = spec -> new AssistSpawner.Handle(counter.incrementAndGet());
            var spawner = new AssistSpawner(reg, new FakeWorktrees(), launcher, ids::pop);

            var a = spawner.assist("parent-1", "slice A", List.of("path:a/**"), List.of("Read"));
            var b = spawner.assist("parent-1", "slice B", List.of("path:b/**"), List.of("Read"));
            assertNotEquals(a.helperId(), b.helperId(), "each assist gets a distinct helper id");
        }
    }
}
