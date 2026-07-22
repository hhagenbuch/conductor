package io.github.hhagenbuch.conductor.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConsentTest {

    @Test
    void grantWritesLocalFileAndGitIgnoresIt(@TempDir Path tmp) throws Exception {
        gitInit(tmp);
        assertFalse(Consent.isGranted(tmp));

        Consent.grant(tmp, "dogfood week");
        assertTrue(Consent.isGranted(tmp));
        assertTrue(Files.exists(tmp.resolve(Consent.FILE)));

        // The consent file is git-ignored locally, so it can never be committed.
        var exclude = Files.readString(tmp.resolve(".git/info/exclude"));
        assertTrue(exclude.contains(Consent.FILE), "consent file must be in .git/info/exclude");
        assertTrue(gitStatusIgnoresConsent(tmp),
                "git must not see the consent file as trackable");
    }

    @Test
    void grantIsIdempotentInExclude(@TempDir Path tmp) throws Exception {
        gitInit(tmp);
        Consent.grant(tmp, null);
        Consent.grant(tmp, null);
        long count = Files.readAllLines(tmp.resolve(".git/info/exclude")).stream()
                .filter(l -> l.trim().equals(Consent.FILE)).count();
        assertEquals(1, count, "exclude entry not duplicated on repeated grant");
    }

    @Test
    void revokeRemovesConsent(@TempDir Path tmp) throws Exception {
        gitInit(tmp);
        Consent.grant(tmp, null);
        Consent.revoke(tmp);
        assertFalse(Consent.isGranted(tmp));
    }

    @Test
    void nonGitProjectStillGetsConsentFile(@TempDir Path tmp) throws Exception {
        Consent.grant(tmp, null);
        assertTrue(Consent.isGranted(tmp), "a non-git dir still records consent locally");
    }

    private void gitInit(Path dir) throws Exception {
        run(dir, "git", "init", "-q");
    }

    private boolean gitStatusIgnoresConsent(Path dir) throws Exception {
        var p = new ProcessBuilder("git", "status", "--porcelain", "--ignored")
                .directory(dir.toFile()).redirectErrorStream(true).start();
        p.waitFor(10, TimeUnit.SECONDS);
        var out = new String(p.getInputStream().readAllBytes());
        // The consent file should appear as ignored (!!) or not at all, never as
        // an untracked-to-be-added (??) entry.
        return out.lines().noneMatch(l -> l.startsWith("??") && l.contains(Consent.FILE));
    }

    private void run(Path dir, String... cmd) throws Exception {
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
    }
}
