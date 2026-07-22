package io.github.hhagenbuch.conductor.lease;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// The lease-enforcement brain: given a proposed tool call and the leases held
/// by OTHER sessions in the same repo, decide whether to block and compose the
/// message the model will see. Pure and side-effect free so it is exhaustively
/// unit-testable; the daemon feeds it the leases and the caller's context.
///
/// Firm for file edits (Write|Edit vs path:/repo: leases); best-effort for git
/// operations (Bash classifier vs branch:/repo: leases). Path leases never
/// block git commands and branch leases never block file writes ... each signal
/// maps only to the actions it can actually reason about.
public final class Enforcer {

    public record Decision(boolean block, String reason) {
        static final Decision ALLOW = new Decision(false, null);
    }

    /// @param repoRoot     caller's canonical repo root (lease repoId)
    /// @param currentBranch caller's branch, or null
    /// @param toolName     PreToolUse tool_name (Write, Edit, Bash, ...)
    /// @param filePath     tool_input.file_path for Write/Edit, else null
    /// @param command      tool_input.command for Bash, else null
    /// @param othersLeases active leases in this repo held by OTHER sessions
    /// @param holderLabel  resolves a session id to a short human label + task
    public static Decision decide(String repoRoot, String currentBranch, String toolName,
                                  String filePath, String command,
                                  List<Lease> othersLeases, HolderLabel holderLabel) {
        if (othersLeases.isEmpty()) {
            return Decision.ALLOW;
        }
        return switch (toolName) {
            case "Write", "Edit", "MultiEdit", "NotebookEdit" ->
                    decideFileEdit(repoRoot, filePath, othersLeases, holderLabel);
            case "Bash" ->
                    decideGit(currentBranch, command, othersLeases, holderLabel);
            default -> Decision.ALLOW;
        };
    }

    private static Decision decideFileEdit(String repoRoot, String filePath,
                                           List<Lease> others, HolderLabel label) {
        if (filePath == null) {
            return Decision.ALLOW;
        }
        var rel = repoRelative(repoRoot, filePath);
        for (var lease : others) {
            if (lease.kind() == Lease.Kind.REPO) {
                return block(lease, label, "the whole repository is leased");
            }
            if (lease.kind() == Lease.Kind.PATH && rel != null && lease.coversPath(rel)) {
                return block(lease, label, "the file " + rel + " is covered by " + lease.scopeString());
            }
        }
        return Decision.ALLOW;
    }

    private static Decision decideGit(String currentBranch, String command,
                                      List<Lease> others, HolderLabel label) {
        var verdict = GitClassifier.classify(command);
        if (!verdict.isHistoryMoving() && !verdict.createsBranch()) {
            return Decision.ALLOW;
        }
        for (var lease : others) {
            if (lease.kind() == Lease.Kind.REPO) {
                return block(lease, label, "a `git " + verdict.subcommand()
                        + "` moves history and the whole repository is leased");
            }
            if (lease.kind() == Lease.Kind.BRANCH) {
                var affected = verdict.createsBranch() ? verdict.targetBranch() : currentBranch;
                if (affected != null && affected.equals(lease.pattern())) {
                    return block(lease, label, "a `git " + verdict.subcommand()
                            + "` touches branch " + affected + ", leased as " + lease.scopeString());
                }
            }
        }
        return Decision.ALLOW;
    }

    private static Decision block(Lease lease, HolderLabel label, String because) {
        var who = label.describe(lease.sessionId());
        var mins = Math.max(0, (lease.expiresAt() - System.currentTimeMillis()) / 60_000);
        var note = lease.note() == null || lease.note().isBlank() ? "" : " (task: " + lease.note() + ")";
        return new Decision(true,
                "LEASE CONFLICT: session " + who + note + " holds " + lease.scopeString()
                + "; " + because + ". The lease expires in ~" + mins + "m. "
                + "Coordinate first: use conductor `post` to message " + shortId(lease.sessionId())
                + ", or `claim` disjoint work. Do not edit here until they release it.");
    }

    /// Canonicalize the absolute file path and express it relative to the repo
    /// root, with `/` separators, so globs match regardless of symlinks or
    /// `..` segments. Returns null if the path is outside the repo.
    static String repoRelative(String repoRoot, String filePath) {
        try {
            var root = Path.of(repoRoot).toAbsolutePath().normalize();
            var target = Path.of(filePath).toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                return null;
            }
            return root.relativize(target).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.length() <= 8 ? id : id.substring(0, 8);
    }

    @FunctionalInterface
    public interface HolderLabel {
        String describe(String sessionId);
    }

    public static Optional<String> reasonIfBlocked(Decision d) {
        return d.block() ? Optional.of(d.reason()) : Optional.empty();
    }

    private Enforcer() { }
}
