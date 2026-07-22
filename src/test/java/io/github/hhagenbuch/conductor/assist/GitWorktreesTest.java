package io.github.hhagenbuch.conductor.assist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GitWorktreesTest {

    @Test
    void addsAndRemovesARealWorktreeOnItsOwnBranch(@TempDir Path tmp) throws Exception {
        var repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        git(repo, "checkout", "-q", "-b", "main");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init", "--allow-empty");

        var worktrees = new GitWorktrees(repo);
        var where = tmp.resolve("repo-assist");
        var result = worktrees.add(where, "feature/assist-helper1");

        assertTrue(Files.isDirectory(result), "worktree dir created");
        assertTrue(Files.exists(result.resolve(".git")), "worktree has a .git link");
        // The worktree is on its own branch, isolated from the parent's tree.
        assertEquals("feature/assist-helper1", currentBranch(where));
        assertEquals("main", currentBranch(repo), "parent stays on its branch");

        worktrees.remove(where);
        assertFalse(Files.exists(where), "worktree removed");
    }

    private String currentBranch(Path dir) throws Exception {
        var p = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(dir.toFile()).redirectErrorStream(true).start();
        p.waitFor(10, TimeUnit.SECONDS);
        return new String(p.getInputStream().readAllBytes()).trim();
    }

    private void git(Path dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
    }
}
