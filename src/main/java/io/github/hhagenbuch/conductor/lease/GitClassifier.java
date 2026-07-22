package io.github.hhagenbuch.conductor.lease;

import java.util.List;
import java.util.Set;

/// Best-effort classification of a Bash command string into the git
/// operations that move branches or history. This is the tripwire that covers
/// the incident class the Write|Edit matcher misses (both war stories were git
/// commands), and it is honestly imperfect: `sed -i`, scripts that shell out
/// to git, unusual aliases, and exotic compound commands can evade it. It is
/// NOT a security boundary. Anything it cannot parse is treated as harmless
/// (fail open): the classifier never invents a conflict it is unsure of.
public final class GitClassifier {

    /// git subcommands that move a branch ref or rewrite/publish history.
    private static final Set<String> HISTORY_MOVING = Set.of(
            "push", "merge", "rebase", "reset", "commit", "cherry-pick", "revert");

    public record Verdict(boolean isHistoryMoving, String subcommand, boolean createsBranch,
                          String targetBranch) {
        static final Verdict NONE = new Verdict(false, null, false, null);
    }

    /// Classify a single command. Handles the common shapes: a bare `git <sub>`,
    /// and `git checkout -b <name>` / `git switch -c <name>` as branch creation.
    /// Compound commands (`&&`, `;`, `|`) are split and the strongest verdict wins.
    public static Verdict classify(String command) {
        if (command == null || command.isBlank()) {
            return Verdict.NONE;
        }
        Verdict strongest = Verdict.NONE;
        for (var segment : splitCompound(command)) {
            var v = classifySegment(segment.trim());
            if (v.isHistoryMoving() || v.createsBranch()) {
                // history-moving is the stronger signal for enforcement
                if (!strongest.isHistoryMoving()) {
                    strongest = v;
                }
            }
        }
        return strongest;
    }

    private static Verdict classifySegment(String seg) {
        var tokens = tokenize(seg);
        int gi = indexOfGit(tokens);
        if (gi < 0 || gi + 1 >= tokens.size()) {
            return Verdict.NONE;
        }
        // First non-flag token after `git` is the subcommand.
        String sub = null;
        int subIdx = -1;
        for (int i = gi + 1; i < tokens.size(); i++) {
            var t = tokens.get(i);
            if (t.startsWith("-")) {
                continue;
            }
            sub = t;
            subIdx = i;
            break;
        }
        if (sub == null) {
            return Verdict.NONE;
        }
        boolean createsBranch = false;
        String targetBranch = null;
        if (sub.equals("checkout") || sub.equals("switch")) {
            var flag = sub.equals("checkout") ? "-b" : "-c";
            for (int i = subIdx + 1; i < tokens.size(); i++) {
                if (tokens.get(i).equals(flag) && i + 1 < tokens.size()) {
                    createsBranch = true;
                    targetBranch = tokens.get(i + 1);
                    break;
                }
            }
        }
        boolean historyMoving = HISTORY_MOVING.contains(sub);
        if (!historyMoving && !createsBranch) {
            return Verdict.NONE;
        }
        return new Verdict(historyMoving, sub, createsBranch, targetBranch);
    }

    private static int indexOfGit(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            var t = tokens.get(i);
            // bare `git`, or a path ending in /git; ignore env assignments before it
            if (t.equals("git") || t.endsWith("/git")) {
                return i;
            }
            if (t.contains("=")) {
                continue; // FOO=bar env prefix
            }
            // a non-git leading command means this segment is not a git call
            return -1;
        }
        return -1;
    }

    private static List<String> splitCompound(String command) {
        return List.of(command.split("&&|\\|\\||;|\\|"));
    }

    /// Whitespace tokenizer that drops surrounding quotes; sufficient for the
    /// classification we do (we never execute these tokens).
    private static List<String> tokenize(String seg) {
        var out = new java.util.ArrayList<String>();
        for (var raw : seg.trim().split("\\s+")) {
            if (raw.isEmpty()) {
                continue;
            }
            var t = raw;
            if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'')
                    && t.charAt(t.length() - 1) == t.charAt(0)) {
                t = t.substring(1, t.length() - 1);
            }
            out.add(t);
        }
        return out;
    }

    private GitClassifier() { }
}
