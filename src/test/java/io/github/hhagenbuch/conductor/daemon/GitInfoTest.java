package io.github.hhagenbuch.conductor.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GitInfoTest {

    @Test
    void nonGitDirFallsBackToCanonicalPath(@TempDir Path tmp) {
        var info = GitInfo.of(tmp.toString());
        assertNotNull(info.repoIdentity());
        // Not a git repo: identity is the dir itself, branch unknown.
        assertTrue(info.repoIdentity().contains(tmp.getFileName().toString()));
    }

    @Test
    void twoWorktreesOfOneRepoShareIdentity(@TempDir Path tmp) throws Exception {
        var main = tmp.resolve("main");
        Files.createDirectories(main);
        git(main, "init", "-q");
        git(main, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init", "--allow-empty");
        git(main, "branch", "feature/x");

        var linked = tmp.resolve("linked");
        git(main, "worktree", "add", "-q", linked.toString(), "feature/x");

        var idMain = GitInfo.of(main.toString());
        var idLinked = GitInfo.of(linked.toString());

        assertEquals(idMain.repoIdentity(), idLinked.repoIdentity(),
                "worktrees of one repo must resolve to the same repo identity");
        // ...while still reporting their own distinct branches.
        assertNotEquals(idMain.branch(), idLinked.branch());
        assertFalse(idMain.repoIdentity().endsWith(".git"),
                "identity should be the repo root, not the .git dir");
    }

    private static void git(Path dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        var p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), "git " + String.join(" ", args)
                + " -> " + new String(p.getInputStream().readAllBytes()));
    }
}
