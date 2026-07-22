package io.github.hhagenbuch.conductor.cli;

import io.github.hhagenbuch.conductor.transcript.Consent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/// `conductor observe [project-dir] [--revoke] [--note "..."]`: grant or
/// withdraw consent for conductor to tail a project's transcripts. Consent is
/// a single LOCAL-ONLY file that this command also git-ignores, so it can
/// never be committed onto another contributor.
public final class ObserveCommand {

    public static int run(String[] args) {
        boolean revoke = Arrays.asList(args).contains("--revoke");
        String note = null;
        Path project = Path.of("").toAbsolutePath();
        for (int i = 0; i < args.length; i++) {
            var a = args[i];
            if (a.equals("--revoke")) {
                continue;
            }
            if (a.equals("--note") && i + 1 < args.length) {
                note = args[++i];
            } else if (!a.startsWith("--")) {
                project = Path.of(a).toAbsolutePath().normalize();
            }
        }
        try {
            if (revoke) {
                Consent.revoke(project);
                System.out.println("conductor: transcript observation revoked for " + project);
                System.out.println("The " + Consent.FILE + " file was removed; the tailer will not open this project's transcripts.");
                return 0;
            }
            Consent.grant(project, note);
            System.out.println("conductor: transcript observation CONSENTED for " + project);
            System.out.println("  wrote " + project.resolve(Consent.FILE) + " (local-only, git-ignored)");
            System.out.println("Redacted digests of this project's sessions can now feed `brief_me`.");
            System.out.println("Sessions here will show as `observed` in `conductor ps`. Revoke with --revoke.");
            return 0;
        } catch (IOException e) {
            System.err.println("conductor observe failed: " + e.getMessage());
            return 1;
        }
    }

    private ObserveCommand() {
    }
}
