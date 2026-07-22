package io.github.hhagenbuch.conductor.lease;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

/// An advisory lock a session holds on a scope within one repository.
///
/// Scope grammar (as typed into `claim`):
///   repo:                    the whole repository
///   path:<glob>              files matching the glob, relative to the repo root
///   branch:<name>            a named branch
///
/// Every lease is anchored to a `repoId` (the holder's canonical repo identity,
/// from the git common dir) so a `path:` glob in one repo never matches a file
/// in another. TTL is absolute wall-clock expiry; there is no renewal in v1.
public record Lease(long id, String sessionId, String repoId, Kind kind, String pattern,
                    long createdAt, long expiresAt, String note) {

    public enum Kind { REPO, PATH, BRANCH }

    /// Parse a `claim` scope string into (kind, pattern). Throws on nonsense so
    /// the bus tool can return a helpful error rather than store a dead lease.
    public static Parsed parseScope(String scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        var s = scope.trim();
        if (s.equals("repo:") || s.equals("repo")) {
            return new Parsed(Kind.REPO, "");
        }
        if (s.startsWith("path:")) {
            var glob = s.substring("path:".length()).trim();
            if (glob.isEmpty()) {
                throw new IllegalArgumentException("path: scope needs a glob, e.g. path:src/main/**");
            }
            return new Parsed(Kind.PATH, glob);
        }
        if (s.startsWith("branch:")) {
            var b = s.substring("branch:".length()).trim();
            if (b.isEmpty()) {
                throw new IllegalArgumentException("branch: scope needs a name, e.g. branch:feature/x");
            }
            return new Parsed(Kind.BRANCH, b);
        }
        throw new IllegalArgumentException(
                "unknown scope '" + scope + "' (use repo:, path:<glob>, or branch:<name>)");
    }

    public record Parsed(Kind kind, String pattern) { }

    /// Human display of the scope, the inverse of parseScope.
    public String scopeString() {
        return switch (kind) {
            case REPO -> "repo:";
            case PATH -> "path:" + pattern;
            case BRANCH -> "branch:" + pattern;
        };
    }

    /// Does this PATH lease cover a repo-relative file path? Uses glob
    /// semantics where `**` crosses directory boundaries.
    public boolean coversPath(String repoRelative) {
        if (kind != Kind.PATH) {
            return false;
        }
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        return m.matches(Path.of(repoRelative));
    }
}
