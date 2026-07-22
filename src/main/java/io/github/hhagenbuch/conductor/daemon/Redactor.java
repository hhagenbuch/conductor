package io.github.hhagenbuch.conductor.daemon;

import io.github.hhagenbuch.blackbox.redact.Scrubber;

/// Redaction for everything conductor stores or shows about a session
/// (activity digests, transcript-derived digests). This is a thin delegate to
/// agent-blackbox's `blackbox-redact` library ... the same patterns that scrub
/// blackbox trace events, reused here on plain strings. Nothing unredacted is
/// ever persisted by conductor.
///
/// The credentials pattern set is used (not the narrow default) because the
/// text scrubbed here is free-form assistant output and user prompts, where
/// provider tokens, cloud keys, JWTs, and `key = value` secrets all appear.
public final class Redactor {

    private static final Scrubber SCRUBBER = Scrubber.forCredentials();

    public static String redact(String s) {
        return SCRUBBER.apply(s);
    }

    /// Whether scrubbing `s` would remove anything (used by the CI privacy
    /// guard to prove a seeded secret does not survive).
    public static boolean wouldRedact(String s) {
        return SCRUBBER.scrub(s).redacted();
    }

    private Redactor() {
    }
}
