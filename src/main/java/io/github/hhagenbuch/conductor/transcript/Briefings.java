package io.github.hhagenbuch.conductor.transcript;

import io.github.hhagenbuch.conductor.daemon.Registry;

import java.nio.file.Path;
import java.sql.SQLException;

/// Builds a briefing bundle for a session from the registry + its consented,
/// redacted transcript digest + the leases it holds. Shared by the `brief_me`
/// endpoint and the assist spawner so both compose identical bundles.
public final class Briefings {

    public record Result(String bundle, boolean consented, boolean tailed) { }

    /// Compose a briefing for `session`. Reads the transcript only if the
    /// session's project has local consent; otherwise the bundle is
    /// registry-only. Does NOT itself mark the session observed ... the caller
    /// decides (the daemon marks on a served brief).
    public static Result compose(Registry registry, Registry.Session session, long now) throws SQLException {
        var projectRoot = session.worktree() != null ? Path.of(session.worktree())
                                                     : Path.of(session.projectDir());
        boolean consented = Consent.isGranted(projectRoot);

        TranscriptDigest.Digest digest = TranscriptDigest.Digest.empty();
        boolean tailed = false;
        if (consented && session.transcriptPath() != null) {
            try {
                digest = TranscriptDigest.fromFile(Path.of(session.transcriptPath()));
                tailed = true;
            } catch (Exception ignored) {
                // an unreadable transcript degrades to a registry-only briefing
            }
        }

        var leaseLines = registry.activeLeases(session.projectDir()).stream()
                .filter(l -> l.sessionId().equals(session.sessionId()))
                .map(l -> new BriefingComposer.LeaseLine(l.scopeString(), l.note()))
                .toList();

        var parent = new BriefingComposer.Parent(session.sessionId(), session.projectDir(),
                session.gitBranch(), session.statedTask(), session.lastSeen(), consented);
        var bundle = BriefingComposer.compose(parent, digest, leaseLines, consented, now);
        return new Result(bundle, consented, tailed);
    }

    private Briefings() {
    }
}
