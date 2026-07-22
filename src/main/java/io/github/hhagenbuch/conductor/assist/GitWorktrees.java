package io.github.hhagenbuch.conductor.assist;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/// Thin wrapper over `git worktree`. A helper works in its own worktree on its
/// own branch and integrates via PR ... it never edits the parent's tree. This
/// is the structural guarantee that retires the stale-local-main incident
/// class: two coordinated sessions never share one checkout.
public final class GitWorktrees implements Worktrees {

    private final Path parentRepo;

    public GitWorktrees(Path parentRepo) {
        this.parentRepo = parentRepo;
    }

    /// Create a worktree at `where` on a new `branch` cut from origin/main if
    /// it exists, else the current HEAD. Returns the worktree path.
    @Override
    public Path add(Path where, String branch) throws IOException, InterruptedException {
        var base = hasOriginMain() ? "origin/main" : "HEAD";
        run("git", "worktree", "add", "-b", branch, where.toString(), base);
        return where;
    }

    @Override
    public void remove(Path where) throws IOException, InterruptedException {
        run("git", "worktree", "remove", "--force", where.toString());
    }

    /// Best-effort cleanup used on failure paths; never throws.
    @Override
    public void removeQuietly(Path where) {
        try {
            remove(where);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean hasOriginMain() {
        try {
            return exit("git", "rev-parse", "--verify", "--quiet", "origin/main") == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private void run(String... cmd) throws IOException, InterruptedException {
        int code = exit(cmd);
        if (code != 0) {
            throw new IOException("command failed (" + code + "): " + String.join(" ", cmd));
        }
    }

    private int exit(String... cmd) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(cmd).directory(parentRepo.toFile()).redirectErrorStream(true);
        var p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }
}
