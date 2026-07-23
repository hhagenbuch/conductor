package io.github.hhagenbuch.conductor.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The flock throttle ledger and snooze store on the registry.
class FlockRegistryTest {

    private Registry registry(Path tmp) throws Exception {
        return new Registry(tmp.resolve("bus.db"), Clock.systemUTC());
    }

    @Test
    void throttleAllowsOnceThenSuppressesWithinWindow(@TempDir Path tmp) throws Exception {
        try (var reg = registry(tmp)) {
            assertTrue(reg.flockAllowAndRecord("A", "Symbol:X", "B", 60_000), "first alert allowed");
            assertFalse(reg.flockAllowAndRecord("A", "Symbol:X", "B", 60_000), "repeat within window suppressed");
            // a different consumer for the same change is its own throttle key
            assertTrue(reg.flockAllowAndRecord("A", "Symbol:X", "C", 60_000), "distinct consumer is independent");
            // a zero window is effectively no throttle: the prior post is never "within"
            assertTrue(reg.flockAllowAndRecord("A", "Symbol:X", "B", 0), "zero window never throttles");
        }
    }

    @Test
    void snoozeIsPerSessionAndExpires(@TempDir Path tmp) throws Exception {
        try (var reg = registry(tmp)) {
            assertFalse(reg.flockSnoozed("B", "Symbol:X"), "nothing snoozed initially");
            reg.flockSnooze("B", "Symbol:X", System.currentTimeMillis() + 3_600_000);
            assertTrue(reg.flockSnoozed("B", "Symbol:X"), "active snooze mutes");
            assertFalse(reg.flockSnoozed("C", "Symbol:X"), "another session is unaffected");
            assertFalse(reg.flockSnoozed("B", "Symbol:Y"), "another entity is unaffected");

            reg.flockSnooze("B", "Symbol:X", System.currentTimeMillis() - 1_000); // already expired
            assertFalse(reg.flockSnoozed("B", "Symbol:X"), "expired snooze no longer mutes");
        }
    }
}
