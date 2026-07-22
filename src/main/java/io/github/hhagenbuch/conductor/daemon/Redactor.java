package io.github.hhagenbuch.conductor.daemon;

import java.util.List;
import java.util.regex.Pattern;

/// Minimal credential-shape redaction applied to every activity digest
/// before it is stored or shown. Phase 3 replaces this with the
/// agent-blackbox redaction library as a dependency; the invariant is the
/// same from day one: nothing unredacted is ever persisted by conductor.
public final class Redactor {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{10,}"),
            Pattern.compile("sk-[A-Za-z0-9]{20,}"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("AKIA[A-Z0-9]{16}"),
            Pattern.compile("xox[bap]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)(password|passwd|secret|token|api[_-]?key)\\s*[=:]\\s*\\S+"));

    public static String redact(String s) {
        if (s == null) {
            return null;
        }
        var out = s;
        for (var p : PATTERNS) {
            out = p.matcher(out).replaceAll("[REDACTED]");
        }
        return out;
    }

    private Redactor() { }
}
