package io.github.hhagenbuch.conductor.transcript;

import io.github.hhagenbuch.conductor.daemon.Registry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

/// Impact-awareness seam (b): {@link Briefings#changedFiles}. Consent-gated,
/// redacted, never-throws.
class BriefingsChangedFilesTest {

    private Registry.Session session(String transcriptPath, String projectRoot) {
        return new Registry.Session("s1", projectRoot, "main", projectRoot, "task",
                transcriptPath, 0, 0, false, false, null, null, "active");
    }

    @Test
    void returnsFilesTouchedWhenConsented(@TempDir Path tmp) throws Exception {
        var project = tmp.resolve("proj");
        Files.createDirectories(project);
        var transcript = project.resolve("t.jsonl");
        Files.copy(Path.of("src/test/resources/fixtures/synthetic-session.jsonl"), transcript);
        Consent.grant(project, "impact test");

        var files = Briefings.changedFiles(session(transcript.toString(), project.toString()));
        assertFalse(files.isEmpty(), "consented session yields its changed files");
        assertTrue(files.stream().anyMatch(f -> f.contains("HttpClient.java")), files.toString());
    }

    @Test
    void emptyWithoutConsent(@TempDir Path tmp) throws Exception {
        var project = tmp.resolve("proj");
        Files.createDirectories(project);
        var transcript = project.resolve("t.jsonl");
        Files.copy(Path.of("src/test/resources/fixtures/synthetic-session.jsonl"), transcript);
        // no Consent.grant -> the seam must read nothing (privacy rule)
        assertTrue(Briefings.changedFiles(session(transcript.toString(), project.toString())).isEmpty());
    }

    @Test
    void emptyAndSafeOnMissingTranscriptOrNull() {
        assertTrue(Briefings.changedFiles(null).isEmpty());
        assertTrue(Briefings.changedFiles(session(null, "/nope")).isEmpty());
        assertTrue(Briefings.changedFiles(session("/nonexistent.jsonl", "/nope")).isEmpty());
    }
}
