package io.github.hhagenbuch.conductor.assist;

import io.github.hhagenbuch.conductor.daemon.Registry;
import io.github.hhagenbuch.conductor.lease.Lease;
import io.github.hhagenbuch.conductor.transcript.Briefings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/// Spawns a helper session to finish a running job faster. The human (or the
/// calling agent) names the work split and the helper's lease scopes; v1 does
/// no auto-decomposition. The helper works in its own git worktree on its own
/// branch and integrates via PR ... it never edits the parent's tree.
///
/// Ordering is chosen for safe cleanup. The helper's id is minted fresh (a used
/// `--session-id` can never be reclaimed ... GROUND-TRUTH §3), the worktree and
/// registration happen before launch, and EVERY failure path releases the
/// helper's leases and removes the worktree, so a spawn that dies between
/// register and a live process leaves no phantom holding leases.
public final class AssistSpawner {

    /// Launches the headless helper. Injected so tests can drive success and
    /// failure without a real `claude` process.
    @FunctionalInterface
    public interface Launcher {
        /// @return the launched process handle (or a descriptor); throwing
        ///         signals a spawn failure that must trigger cleanup.
        Handle launch(Spec spec) throws Exception;
    }

    public record Spec(String helperId, String parentId, Path worktree, Path briefingFile, String task,
                       List<String> allowedTools) { }

    public record Handle(long pid) { }

    public record Result(String helperId, String branch, Path worktree, Path briefingFile,
                         long pid) { }

    /// Default helper lease TTL: long enough to finish a slice, short enough
    /// that a crashed helper frees its scopes.
    private static final long HELPER_LEASE_TTL_MS = 90 * 60_000;

    private final Registry registry;
    private final Worktrees.Factory worktrees;
    private final Launcher launcher;
    private final Supplier<String> idSource;

    public AssistSpawner(Registry registry, Worktrees.Factory worktrees, Launcher launcher,
                         Supplier<String> idSource) {
        this.registry = registry;
        this.worktrees = worktrees;
        this.launcher = launcher;
        this.idSource = idSource;
    }

    public Result assist(String parentId, String task, List<String> helperScopes,
                         List<String> allowedTools) throws Exception {
        var parent = registry.session(parentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown parent session " + parentId));

        var helperId = idSource.get();
        var branch = "feature/assist-" + helperId.substring(0, Math.min(8, helperId.length()));
        var repo = Path.of(parent.projectDir());
        var git = worktrees.forRepo(repo);
        var worktreePath = repo.resolveSibling(repo.getFileName() + "-assist-"
                + helperId.substring(0, Math.min(8, helperId.length())));

        boolean registered = false;
        Path createdWorktree = null;
        try {
            createdWorktree = git.add(worktreePath, branch);

            // Pre-register the helper and its leases BEFORE launch so the parent
            // sees it immediately. From here a failure could strand a phantom,
            // so everything below is inside the try with cleanup guaranteed.
            registry.register(helperId, parent.projectDir(), branch, createdWorktree.toString(),
                    null, true);
            registered = true;
            for (var scope : helperScopes) {
                var parsed = Lease.parseScope(scope);
                registry.claim(helperId, parent.projectDir(), parsed.kind(), parsed.pattern(),
                        HELPER_LEASE_TTL_MS, "assist: " + task);
            }

            // Brief on the PARENT's work, not the empty helper: the helper
            // inherits the parent's task, progress digest, and leases context.
            var parentBrief = Briefings.compose(registry, parent, System.currentTimeMillis());
            var briefingFile = createdWorktree.resolve(".conductor-briefing.md");
            Files.writeString(briefingFile, parentBrief.bundle() + "\n\n## Your assignment\n" + task
                    + "\n\nWork only within your leased scope. Integrate via PR ... never edit the "
                    + "parent's tree. Use conductor `post` to report progress to " + shortId(parentId)
                    + ", and `brief_me` before decisions that depend on their state.\n");

            var handle = launcher.launch(new Spec(helperId, parentId, createdWorktree, briefingFile,
                    task, allowedTools));

            registry.post("conductor", parentId, "helper " + shortId(helperId) + " has taken: "
                    + task + " (branch " + branch + "). It will integrate via PR; it holds "
                    + helperScopes + ".");
            return new Result(helperId, branch, createdWorktree, briefingFile, handle.pid());
        } catch (Exception failure) {
            // Guaranteed cleanup: no phantom session, no orphaned leases, no
            // dangling worktree. end() releases the helper's leases.
            if (registered) {
                safely(() -> registry.end(helperId));
            }
            if (createdWorktree != null) {
                git.removeQuietly(createdWorktree);
            }
            throw failure;
        }
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.length() <= 8 ? id : id.substring(0, 8);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void safely(Action a) {
        try {
            a.run();
        } catch (Exception ignored) {
            // cleanup is best-effort; a failure here must not mask the original
        }
    }
}
