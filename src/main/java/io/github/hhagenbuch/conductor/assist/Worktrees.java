package io.github.hhagenbuch.conductor.assist;

import java.nio.file.Path;

/// A helper's isolated working tree. Abstracted so the assist spawner can be
/// tested without invoking real git.
public interface Worktrees {

    Path add(Path where, String branch) throws Exception;

    void remove(Path where) throws Exception;

    /// Best-effort cleanup on failure paths; never throws.
    void removeQuietly(Path where);

    /// Produces a Worktrees bound to a specific parent repository.
    @FunctionalInterface
    interface Factory {
        Worktrees forRepo(Path parentRepo);

        static Factory real() {
            return GitWorktrees::new;
        }
    }
}
