package io.github.hhagenbuch.conductor.transcript;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// The privacy guard the DESIGN promises to enforce in CI: transcript content
/// never enters the repo. Every test fixture must be SYNTHETIC ... it may not
/// carry markers of real provenance (a real username, this machine's home
/// path, the real project slug), and any secret seeded into a fixture must not
/// survive redaction into a digest.
///
/// If this test fails, a fixture is either real (delete it, author a synthetic
/// one) or the redaction regressed.
class PrivacyGuardTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    /// Substrings that would betray a real transcript. Kept deliberately
    /// specific to this portfolio/machine so it never false-positives on
    /// legitimate synthetic content.
    private static final List<String> PROVENANCE_MARKERS = List.of(
            "heywardhagenbuch",
            "/Users/heyward",
            "Workspaces/personal",
            "Workspaces/commons",
            "heyward360");

    /// A secret is seeded into synthetic-with-secret.jsonl on purpose; the
    /// digest must scrub it. The raw token must never appear in a digest.
    private static final String SEEDED_SECRET = "sk-ant-SEEDEDsecret0123456789ABCDEF";

    @Test
    void everyFixtureIsSyntheticNoRealProvenance() throws IOException {
        for (var file : fixtureFiles()) {
            var content = Files.readString(file);
            for (var marker : PROVENANCE_MARKERS) {
                assertFalse(content.contains(marker),
                        "fixture " + file.getFileName() + " contains real-provenance marker '"
                        + marker + "' — fixtures must be synthetic");
            }
        }
    }

    @Test
    void seededSecretDoesNotSurviveRedactionIntoADigest() throws IOException {
        var withSecret = FIXTURES.resolve("synthetic-with-secret.jsonl");
        // Sanity: the secret really is in the raw fixture (else this proves nothing).
        assertTrue(Files.readString(withSecret).contains(SEEDED_SECRET),
                "the secret-bearing fixture must actually contain the seeded secret");

        var digest = TranscriptDigest.fromLines(Files.readAllLines(withSecret));
        var rendered = digest.statedTask() + " " + String.join(" ", digest.filesTouched())
                + " " + String.join(" ", digest.recentToolCalls());

        assertFalse(rendered.contains(SEEDED_SECRET),
                "the seeded secret survived redaction into the digest: " + rendered);
        assertTrue(digest.statedTask().contains("[redacted]"),
                "the secret's location should be marked redacted, not dropped: " + digest.statedTask());
    }

    @Test
    void secretIsAlsoScrubbedFromAComposedBriefing() throws IOException {
        var digest = TranscriptDigest.fromFile(FIXTURES.resolve("synthetic-with-secret.jsonl"));
        var parent = new BriefingComposer.Parent("synthetic-bbbb", "/work/widget", "feature/auth",
                digest.statedTask(), 1_000L, true);
        var bundle = BriefingComposer.compose(parent, digest, List.of(), true, 2_000L);
        assertFalse(bundle.contains(SEEDED_SECRET), "briefing bundle leaked the seeded secret");
    }

    private List<Path> fixtureFiles() throws IOException {
        assertTrue(Files.isDirectory(FIXTURES), "fixtures dir missing: " + FIXTURES.toAbsolutePath());
        try (Stream<Path> s = Files.list(FIXTURES)) {
            return s.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList();
        }
    }
}
