package io.github.hhagenbuch.conductor.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/// Git identity for a working directory. Repo identity comes from the git
/// COMMON dir, not the cwd: two worktrees of one repository have different
/// paths but share a common dir, so they must resolve to the same `repo:`
/// scope for leases to work across worktrees (DESIGN § Leases). A directory
/// that is not a git repo falls back to its own canonical path as identity.
public record GitInfo(String repoIdentity, String branch) {

    public static GitInfo of(String cwd) {
        if (cwd == null) {
            return new GitInfo("unknown", null);
        }
        var dir = Path.of(cwd);
        var commonDir = run(dir, "rev-parse", "--git-common-dir");
        var branch = run(dir, "rev-parse", "--abbrev-ref", "HEAD");
        if (commonDir == null) {
            return new GitInfo(canonical(dir), branch);
        }
        // --git-common-dir may be relative to cwd; resolve then canonicalize
        // so every worktree of one repo yields a byte-identical identity. The
        // common dir is the repo's `.git`; strip that trailing segment so the
        // identity reads as the repo root while staying identical across all
        // worktrees (which all share one common dir).
        var resolved = dir.resolve(commonDir).toAbsolutePath().normalize();
        var canonical = canonical(resolved);
        if (resolved.getFileName() != null && resolved.getFileName().toString().equals(".git")
                && resolved.getParent() != null) {
            canonical = canonical(resolved.getParent());
        }
        return new GitInfo(canonical, branch);
    }

    private static String canonical(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize().toString();
        }
    }

    private static String run(Path dir, String... args) {
        try {
            var cmd = new java.util.ArrayList<String>();
            cmd.add("git");
            cmd.addAll(java.util.List.of(args));
            var pb = new ProcessBuilder(cmd).directory(dir.toFile());
            pb.redirectErrorStream(false);
            var proc = pb.start();
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            if (proc.exitValue() != 0) {
                return null;
            }
            var out = new String(proc.getInputStream().readAllBytes()).trim();
            return out.isBlank() ? null : out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /// Test/consistency helper: does this dir resolve to a real git common dir?
    public static boolean isGitRepo(Path dir) {
        return run(dir, "rev-parse", "--git-common-dir") != null && Files.exists(dir);
    }
}
