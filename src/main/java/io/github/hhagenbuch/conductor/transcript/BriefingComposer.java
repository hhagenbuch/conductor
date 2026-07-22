package io.github.hhagenbuch.conductor.transcript;

import io.github.hhagenbuch.conductor.transcript.TranscriptDigest.Digest;

import java.util.List;

/// Composes a helper's briefing bundle from a session's registry entry, its
/// (redacted) transcript digest, and the leases it holds. The bundle is
/// context transfer without context sharing: a joining session gets what the
/// parent is doing, not the parent's raw conversation.
///
/// Every bundle is stamped with when it was generated and how fresh the parent
/// was, and it carries an explicit instruction to re-brief before decisions
/// that depend on the parent's current state ... a briefing is a snapshot.
public final class BriefingComposer {

    public record Parent(String sessionId, String projectDir, String gitBranch,
                         String statedTask, long lastSeen, boolean observed) { }

    public record LeaseLine(String scope, String note) { }

    public static String compose(Parent parent, Digest digest, List<LeaseLine> leases,
                                 boolean consented, long generatedAt) {
        var sb = new StringBuilder();
        sb.append("# conductor briefing: session ").append(shortId(parent.sessionId())).append('\n');
        sb.append("generated_at: ").append(iso(generatedAt)).append('\n');
        sb.append("parent_last_seen: ").append(iso(parent.lastSeen()))
          .append(" (").append(ageOf(parent.lastSeen(), generatedAt)).append(" before this briefing)\n");
        sb.append("project: ").append(parent.projectDir()).append('\n');
        sb.append("branch: ").append(orUnknown(parent.gitBranch())).append("\n\n");

        sb.append("## Task\n");
        var task = firstNonBlank(digest.statedTask(), parent.statedTask());
        sb.append(task == null ? "(not stated)\n" : task + "\n");
        sb.append('\n');

        if (!consented) {
            sb.append("## Awareness tier\n");
            sb.append("This project has NOT consented to transcript observation, so this ")
              .append("briefing is registry-only (stated task and leases). Run ")
              .append("`conductor observe` in the project to enable richer, redacted ")
              .append("digests. No transcript was read.\n\n");
        } else {
            sb.append("## Progress (redacted digest)\n");
            sb.append("- turns: ").append(digest.userTurns()).append(" user / ")
              .append(digest.assistantTurns()).append(" assistant\n");
            if (!digest.filesTouched().isEmpty()) {
                sb.append("- files touched:\n");
                for (var f : digest.filesTouched()) {
                    sb.append("    ").append(f).append('\n');
                }
            }
            if (!digest.recentToolCalls().isEmpty()) {
                sb.append("- recent tool calls: ")
                  .append(String.join(" → ", digest.recentToolCalls())).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## Leases held by the parent\n");
        if (leases.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (var l : leases) {
                sb.append("- ").append(l.scope());
                if (l.note() != null && !l.note().isBlank()) {
                    sb.append("  (").append(l.note()).append(")");
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        sb.append("## Before you act\n");
        sb.append("This briefing is a SNAPSHOT taken at generated_at above. Before any ")
          .append("decision that depends on the parent's current state (has it finished a ")
          .append("migration? released a lease? changed direction?), call `brief_me` again ")
          .append("for a fresh view, and `who_else`/`inbox` to coordinate. Claim your own ")
          .append("lease-disjoint scope before editing; integrate via PR, never by editing ")
          .append("the parent's tree.\n");
        return sb.toString();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private static String orUnknown(String s) {
        return s == null || s.isBlank() ? "(unknown)" : s;
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String iso(long epochMillis) {
        return epochMillis <= 0 ? "(unknown)" : java.time.Instant.ofEpochMilli(epochMillis).toString();
    }

    private static String ageOf(long then, long now) {
        long s = Math.max(0, (now - then) / 1000);
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return (s / 60) + "m";
        }
        return (s / 3600) + "h";
    }

    private BriefingComposer() {
    }
}
