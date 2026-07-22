package io.github.hhagenbuch.conductor.lease;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitClassifierTest {

    @Test
    void flagsHistoryMovingCommands() {
        assertTrue(GitClassifier.classify("git push origin main").isHistoryMoving());
        assertTrue(GitClassifier.classify("git merge feature/x").isHistoryMoving());
        assertTrue(GitClassifier.classify("git rebase main").isHistoryMoving());
        assertTrue(GitClassifier.classify("git reset --hard origin/main").isHistoryMoving());
        assertTrue(GitClassifier.classify("git commit -m 'x'").isHistoryMoving());
    }

    @Test
    void detectsBranchCreation() {
        var co = GitClassifier.classify("git checkout -b feature/y origin/main");
        assertTrue(co.createsBranch());
        assertEquals("feature/y", co.targetBranch());

        var sw = GitClassifier.classify("git switch -c feature/z");
        assertTrue(sw.createsBranch());
        assertEquals("feature/z", sw.targetBranch());
    }

    @Test
    void ignoresReadOnlyAndNonGit() {
        assertFalse(GitClassifier.classify("git status").isHistoryMoving());
        assertFalse(GitClassifier.classify("git log --oneline").isHistoryMoving());
        assertFalse(GitClassifier.classify("git diff HEAD~1").isHistoryMoving());
        assertFalse(GitClassifier.classify("ls -la").isHistoryMoving());
        assertFalse(GitClassifier.classify("echo git push").isHistoryMoving());
    }

    @Test
    void handlesEnvPrefixAndCompound() {
        assertTrue(GitClassifier.classify("GIT_TRACE=1 git push").isHistoryMoving());
        // strongest verdict across a compound command wins
        assertTrue(GitClassifier.classify("cd /repo && git merge main").isHistoryMoving());
        assertTrue(GitClassifier.classify("git fetch && git rebase origin/main").isHistoryMoving());
    }

    @Test
    void nullAndBlankAreHarmless() {
        assertFalse(GitClassifier.classify(null).isHistoryMoving());
        assertFalse(GitClassifier.classify("   ").isHistoryMoving());
    }
}
