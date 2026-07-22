package io.github.hhagenbuch.conductor.assist;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// The real launcher: starts a detached headless `claude -p` in the helper's
/// worktree, under a freshly minted `--session-id`, mounting conductor's MCP
/// shim so the helper has the bus tools.
///
/// It uses scoped `--allowedTools` rather than a blanket permission bypass:
/// GROUND-TRUTH §3 records that `--dangerously-skip-permissions` is policy-
/// blocked here, and an allowlist is both the working and the safer mechanism.
public final class ClaudeLauncher implements AssistSpawner.Launcher {

    private final Path conductorJar;
    private final String model;

    public ClaudeLauncher(Path conductorJar, String model) {
        this.conductorJar = conductorJar;
        this.model = model;
    }

    @Override
    public AssistSpawner.Handle launch(AssistSpawner.Spec spec) throws Exception {
        // Per-invocation MCP config so the helper has the conductor bus without
        // touching any user-scope settings.
        var mcpConfig = spec.worktree().resolve(".conductor-mcp.json");
        Files.writeString(mcpConfig, """
                {"mcpServers":{"conductor":{"type":"stdio","command":"java",
                "args":["-jar","%s","mcp-shim"]}}}""".formatted(conductorJar.toString()));

        var prompt = "You are a conductor helper session. Read .conductor-briefing.md in this "
                + "worktree for your assignment and the parent's context, then do the work. "
                + "Stay within your leased scope; integrate via a pull request; never edit the "
                + "parent's tree. Task: " + spec.task();

        List<String> cmd = new ArrayList<>();
        // Test/demo seam: CONDUCTOR_HELPER_CMD overrides the leaf process so a
        // scripted helper can drive the real orchestration reproducibly (no
        // API key, no network). Production leaves it unset and runs claude.
        var override = System.getenv("CONDUCTOR_HELPER_CMD");
        if (override != null && !override.isBlank()) {
            cmd.addAll(List.of(override.split("\\s+")));
        } else {
            cmd.addAll(List.of(
                    "claude", "-p", prompt,
                    "--session-id", spec.helperId(),
                    "--mcp-config", mcpConfig.toString(),
                    // Isolate the helper to conductor's bus only: without this it
                    // also inherits the user's global MCP servers, which is slow
                    // and leaks unrelated tools into a scoped helper.
                    "--strict-mcp-config",
                    "--model", model,
                    "--output-format", "json"));
            if (!spec.allowedTools().isEmpty()) {
                cmd.add("--allowedTools");
                cmd.add(String.join(",", spec.allowedTools()));
            }
        }

        var log = spec.worktree().resolve(".conductor-helper.log").toFile();
        var pb = new ProcessBuilder(cmd)
                .directory(spec.worktree().toFile())
                .redirectInput(new File("/dev/null")) // headless: don't wait on stdin
                .redirectOutput(log)
                .redirectError(log);
        // Expose the helper's context to an overridden leaf via the environment.
        pb.environment().put("CONDUCTOR_HELPER_ID", spec.helperId());
        pb.environment().put("CONDUCTOR_PARENT_ID", spec.parentId());
        pb.environment().put("CONDUCTOR_WORKTREE", spec.worktree().toString());
        pb.environment().put("CONDUCTOR_BRIEFING", spec.briefingFile().toString());
        pb.environment().put("CONDUCTOR_TASK", spec.task());
        var process = pb.start();
        // Detached: the helper runs on its own and reports over the bus. We do
        // not wait; a failure to START surfaces as an exception the spawner
        // catches and cleans up.
        return new AssistSpawner.Handle(process.pid());
    }

    /// Discover the running conductor jar (the shaded artifact the daemon runs from).
    public static Path selfJar() {
        return Path.of(new File(ClaudeLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath()).getAbsolutePath());
    }
}
