package io.github.hhagenbuch.conductor.transcript;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptDigestTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @Test
    void extractsTaskFilesAndToolsFromSyntheticTranscript() throws Exception {
        var digest = TranscriptDigest.fromFile(FIXTURES.resolve("synthetic-session.jsonl"));

        assertTrue(digest.statedTask().contains("retry"), digest.statedTask());
        assertEquals(2, digest.filesTouched().size(), "two files edited/written");
        assertTrue(digest.filesTouched().stream().anyMatch(f -> f.contains("HttpClient.java")));
        assertTrue(digest.recentToolCalls().contains("Bash"));
        assertEquals(2, digest.userTurns());
        assertEquals(3, digest.assistantTurns());
        assertTrue(digest.lastActivityAt() > 0);
    }

    @Test
    void malformedLinesAreSkippedNotFatal() {
        var digest = TranscriptDigest.fromLines(java.util.List.of(
                "{ not json",
                "",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"do the thing\"}]}}"));
        assertEquals("do the thing", digest.statedTask());
        assertEquals(1, digest.userTurns());
    }

    @Test
    void missingFileIsEmptyDigestNotError() throws Exception {
        var digest = TranscriptDigest.fromFile(Path.of("/nonexistent/transcript.jsonl"));
        assertEquals(TranscriptDigest.Digest.empty().statedTask(), digest.statedTask());
        assertTrue(digest.filesTouched().isEmpty());
    }

    @Test
    void handlesStringContentUserMessages() {
        var digest = TranscriptDigest.fromLines(java.util.List.of(
                "{\"type\":\"user\",\"message\":{\"content\":\"just a plain string prompt\"}}"));
        assertEquals("just a plain string prompt", digest.statedTask());
    }
}
