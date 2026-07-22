package io.github.hhagenbuch.conductor.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RegistryTest {

    /// A clock the test drives by hand, so staleness is deterministic.
    private static final class TestClock extends Clock {
        final AtomicLong now = new AtomicLong(1_000_000L);
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        void advance(Duration d) { now.addAndGet(d.toMillis()); }
    }

    private Registry open(Path dir, TestClock clock) throws Exception {
        return new Registry(dir.resolve("t.db"), clock);
    }

    @Test
    void registerThenListShowsActive(@TempDir Path dir) throws Exception {
        var clock = new TestClock();
        try (var reg = open(dir, clock)) {
            reg.register("s1", "/repo/a", "main", "/repo/a", "/t/s1.jsonl", false);
            var all = reg.sessions("/repo/a");
            assertEquals(1, all.size());
            assertEquals("active", all.getFirst().status());
            assertEquals("main", all.getFirst().gitBranch());
        }
    }

    @Test
    void goesStaleButNeverVanishes(@TempDir Path dir) throws Exception {
        var clock = new TestClock();
        try (var reg = open(dir, clock)) {
            reg.register("s1", "/repo/a", "main", "/repo/a", null, false);
            clock.advance(Duration.ofMillis(Registry.STALE_AFTER_MS + 1));
            var s = reg.sessions("/repo/a").getFirst();
            assertEquals("stale", s.status(), "past TTL is stale, not removed");
        }
    }

    @Test
    void heartbeatRefreshesFreshness(@TempDir Path dir) throws Exception {
        var clock = new TestClock();
        try (var reg = open(dir, clock)) {
            reg.register("s1", "/repo/a", "main", "/repo/a", null, false);
            clock.advance(Duration.ofMillis(Registry.STALE_AFTER_MS - 1));
            reg.heartbeat("s1");
            clock.advance(Duration.ofMillis(2));
            assertEquals("active", reg.sessions("/repo/a").getFirst().status());
        }
    }

    @Test
    void postDeliversByPrefixAndInboxConsumes(@TempDir Path dir) throws Exception {
        var clock = new TestClock();
        try (var reg = open(dir, clock)) {
            reg.register("aaaa1111", "/repo/a", "main", "/repo/a", null, false);
            reg.register("bbbb2222", "/repo/a", "feature/x", "/repo/a", null, false);
            var to = reg.post("aaaa1111", "bbbb", "take the tests");
            assertTrue(to.isPresent());
            assertEquals("bbbb2222", to.get());

            var inbox = reg.inbox("bbbb2222", true);
            assertEquals(1, inbox.size());
            assertEquals("take the tests", inbox.getFirst().body());
            assertTrue(reg.inbox("bbbb2222", true).isEmpty(), "consumed messages do not reappear");
        }
    }

    @Test
    void ambiguousPrefixIsRejected(@TempDir Path dir) throws Exception {
        var clock = new TestClock();
        try (var reg = open(dir, clock)) {
            reg.register("abcd0001", "/repo/a", "main", "/repo/a", null, false);
            reg.register("abcd0002", "/repo/a", "main", "/repo/a", null, false);
            assertTrue(reg.post("x", "abcd", "hi").isEmpty(), "ambiguous prefix delivers to no one");
        }
    }

    @Test
    void activityDigestIsRedactedOnIngest(@TempDir Path dir) throws Exception {
        var clock = new TestClock();
        try (var reg = open(dir, clock)) {
            reg.register("s1", "/repo/a", "main", "/repo/a", null, false);
            reg.recordActivity("s1", "committed with token sk-ant-SECRETKEY0123456789ABCDEF in the log");
            var act = reg.sessions("/repo/a").getFirst().lastActivity();
            assertFalse(act.contains("SECRETKEY0123456789"), "secret must be redacted before storage");
            assertTrue(act.contains("[redacted]"));
        }
    }

    @Test
    void endMarksSessionEnded(@TempDir Path dir) throws Exception {
        var clock = new TestClock();
        try (var reg = open(dir, clock)) {
            reg.register("s1", "/repo/a", "main", "/repo/a", null, false);
            reg.end("s1");
            assertEquals("ended", reg.sessions("/repo/a").getFirst().status());
        }
    }

    @Test
    void claimReleaseAndOwnershipCheck(@TempDir Path dir) throws Exception {
        var clock = new TestClock();
        try (var reg = open(dir, clock)) {
            reg.register("s1", "/repo/a", "main", "/repo/a", null, false);
            var lease = reg.claim("s1", "/repo/a",
                    io.github.hhagenbuch.conductor.lease.Lease.Kind.PATH, "src/**", 60_000, "work");
            assertEquals(1, reg.activeLeases("/repo/a").size());
            // A different session cannot release it.
            assertFalse(reg.release("other", lease.id()));
            assertEquals(1, reg.activeLeases("/repo/a").size());
            // The owner can.
            assertTrue(reg.release("s1", lease.id()));
            assertTrue(reg.activeLeases("/repo/a").isEmpty());
        }
    }

    @Test
    void expiredLeasesArePrunedOnRead(@TempDir Path dir) throws Exception {
        var clock = new TestClock();
        try (var reg = open(dir, clock)) {
            reg.register("s1", "/repo/a", "main", "/repo/a", null, false);
            reg.claim("s1", "/repo/a",
                    io.github.hhagenbuch.conductor.lease.Lease.Kind.REPO, "", 1_000, null);
            assertEquals(1, reg.activeLeases("/repo/a").size());
            clock.advance(Duration.ofMillis(1_001));
            assertTrue(reg.activeLeases("/repo/a").isEmpty(), "expired lease pruned on read (crash recovery)");
        }
    }

    @Test
    void endReleasesTheSessionsLeases(@TempDir Path dir) throws Exception {
        var clock = new TestClock();
        try (var reg = open(dir, clock)) {
            reg.register("s1", "/repo/a", "main", "/repo/a", null, false);
            reg.claim("s1", "/repo/a",
                    io.github.hhagenbuch.conductor.lease.Lease.Kind.REPO, "", 600_000, null);
            reg.end("s1");
            assertTrue(reg.activeLeases("/repo/a").isEmpty(),
                    "a cleanly-ended session leaves no orphaned leases");
        }
    }
}
