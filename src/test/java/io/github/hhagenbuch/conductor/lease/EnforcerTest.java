package io.github.hhagenbuch.conductor.lease;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnforcerTest {

    private static final String REPO = "/repo/x";
    private static final Enforcer.HolderLabel LABEL = id -> id + " (their task)";

    private Lease pathLease(String glob) {
        return new Lease(1, "holder99", REPO, Lease.Kind.PATH, glob,
                0, System.currentTimeMillis() + 3_600_000, "refactor");
    }

    private Lease repoLease() {
        return new Lease(2, "holder99", REPO, Lease.Kind.REPO, "",
                0, System.currentTimeMillis() + 3_600_000, "release cut");
    }

    private Lease branchLease(String name) {
        return new Lease(3, "holder99", REPO, Lease.Kind.BRANCH, name,
                0, System.currentTimeMillis() + 3_600_000, "merging");
    }

    @Test
    void blocksWriteToLeasedPath() {
        var d = Enforcer.decide(REPO, "main", "Write", REPO + "/src/main/App.java", null,
                List.of(pathLease("src/main/**")), LABEL);
        assertTrue(d.block());
        assertTrue(d.reason().contains("LEASE CONFLICT"));
        assertTrue(d.reason().contains("holder99"));
        assertTrue(d.reason().contains("post"), "message should tell the model how to coordinate");
    }

    @Test
    void allowsWriteToDisjointPath() {
        var d = Enforcer.decide(REPO, "main", "Write", REPO + "/src/test/AppTest.java", null,
                List.of(pathLease("src/main/**")), LABEL);
        assertFalse(d.block(), "a disjoint path is not blocked: " + d.reason());
    }

    @Test
    void repoLeaseBlocksAnyWrite() {
        var d = Enforcer.decide(REPO, "main", "Edit", REPO + "/anything.txt", null,
                List.of(repoLease()), LABEL);
        assertTrue(d.block());
    }

    @Test
    void branchLeaseDoesNotBlockFileEdits() {
        // A branch lease is about git operations, not file contents.
        var d = Enforcer.decide(REPO, "main", "Write", REPO + "/src/main/App.java", null,
                List.of(branchLease("main")), LABEL);
        assertFalse(d.block());
    }

    @Test
    void blocksGitMergeOnLeasedBranch() {
        var d = Enforcer.decide(REPO, "main", "Bash", null, "git merge feature/x",
                List.of(branchLease("main")), LABEL);
        assertTrue(d.block());
        assertTrue(d.reason().contains("git merge"));
    }

    @Test
    void allowsGitMergeOnUnleasedBranch() {
        var d = Enforcer.decide(REPO, "develop", "Bash", null, "git merge feature/x",
                List.of(branchLease("main")), LABEL);
        assertFalse(d.block(), "current branch develop is not leased: " + d.reason());
    }

    @Test
    void repoLeaseBlocksHistoryMovingGit() {
        var d = Enforcer.decide(REPO, "main", "Bash", null, "git push origin main",
                List.of(repoLease()), LABEL);
        assertTrue(d.block());
    }

    @Test
    void pathLeaseDoesNotBlockGit() {
        // Path leases don't map cleanly onto git operations; best-effort scope.
        var d = Enforcer.decide(REPO, "main", "Bash", null, "git push origin main",
                List.of(pathLease("src/**")), LABEL);
        assertFalse(d.block());
    }

    @Test
    void readOnlyBashIsNeverBlocked() {
        var d = Enforcer.decide(REPO, "main", "Bash", null, "git status",
                List.of(repoLease()), LABEL);
        assertFalse(d.block());
    }

    @Test
    void noLeasesAlwaysAllows() {
        var d = Enforcer.decide(REPO, "main", "Write", REPO + "/src/main/App.java", null,
                List.of(), LABEL);
        assertFalse(d.block());
    }

    @Test
    void pathCanonicalizationHandlesDotSegments() {
        var d = Enforcer.decide(REPO, "main", "Write", REPO + "/src/main/../main/App.java", null,
                List.of(pathLease("src/main/**")), LABEL);
        assertTrue(d.block(), "../ segments must canonicalize before matching");
    }

    @Test
    void pathOutsideRepoIsNotMatched() {
        var d = Enforcer.decide(REPO, "main", "Write", "/etc/passwd", null,
                List.of(pathLease("**")), LABEL);
        assertFalse(d.block(), "a path outside the repo root is not covered by the repo's leases");
    }
}
