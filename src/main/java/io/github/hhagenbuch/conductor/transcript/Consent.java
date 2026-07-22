package io.github.hhagenbuch.conductor.transcript;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/// Per-project, per-user, per-machine consent to tail transcripts.
///
/// Consent is a single local file, `.conductor-consent`, in the project root.
/// It is **never committable**: `grant` adds it to the repo's
/// `.git/info/exclude` (a local, uncommitted ignore list) itself. Consent to
/// read one user's local transcripts cannot be given on another user's behalf,
/// so a committed consent file would be a privacy bug ... there is no committed
/// variant. Without the local file, the tailer never opens the project's
/// transcripts and its sessions are registry-only.
public final class Consent {

    public static final String FILE = ".conductor-consent";
    private static final String EXCLUDE_LINE = FILE;

    /// True if this project (identified by its root path) has a local consent
    /// file. `projectRoot` is the repo root; transcripts under it may be tailed.
    public static boolean isGranted(Path projectRoot) {
        return projectRoot != null && Files.exists(projectRoot.resolve(FILE));
    }

    /// Write the consent file and ensure it is git-ignored locally. Idempotent.
    public static void grant(Path projectRoot, String note) throws IOException {
        var file = projectRoot.resolve(FILE);
        Files.writeString(file, """
                # conductor transcript-observation consent (LOCAL ONLY).
                # This file opts THIS machine's user in to conductor tailing this
                # project's Claude Code transcripts, redacting on ingest. It is
                # git-ignored on purpose and must never be committed: consent to
                # read local transcripts cannot be granted for other contributors.
                # Remove this file (or run `conductor observe --revoke`) to stop.
                """ + (note == null || note.isBlank() ? "" : "# note: " + note + "\n"));
        ensureGitIgnored(projectRoot);
    }

    public static void revoke(Path projectRoot) throws IOException {
        Files.deleteIfExists(projectRoot.resolve(FILE));
    }

    /// Append the consent filename to `.git/info/exclude` if the project is a
    /// git repo and it is not already excluded. Best-effort: a non-git project
    /// still gets the consent file (there is nothing to accidentally commit).
    private static void ensureGitIgnored(Path projectRoot) throws IOException {
        var gitDir = projectRoot.resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            return;
        }
        var info = gitDir.resolve("info");
        Files.createDirectories(info);
        var exclude = info.resolve("exclude");
        if (Files.exists(exclude)) {
            List<String> lines = Files.readAllLines(exclude);
            if (lines.stream().map(String::trim).anyMatch(EXCLUDE_LINE::equals)) {
                return;
            }
        }
        Files.writeString(exclude, EXCLUDE_LINE + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Consent() {
    }
}
