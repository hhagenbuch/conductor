package io.github.hhagenbuch.conductor.daemon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedactorTest {

    @Test
    void redactsCommonSecretShapes() {
        assertRedacted("sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUV");
        assertRedacted("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        assertRedacted("AKIAIOSFODNN7EXAMPLE");
        assertRedacted("password = hunter2hunter2");
        assertRedacted("xoxb-1234567890-abcdefghij");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        var s = "Refactored AgentLoop and added a test for the retry path.";
        assertEquals(s, Redactor.redact(s));
    }

    @Test
    void nullSafe() {
        assertNull(Redactor.redact(null));
    }

    private void assertRedacted(String secretBearing) {
        var out = Redactor.redact(secretBearing);
        assertTrue(out.contains("[redacted]"), "expected redaction in: " + out);
        assertTrue(Redactor.wouldRedact(secretBearing));
    }
}
