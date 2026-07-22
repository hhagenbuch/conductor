package io.github.hhagenbuch.conductor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.conductor.ConductorHome;
import io.github.hhagenbuch.conductor.shim.DaemonClient;

import java.util.ArrayList;
import java.util.List;

/// `conductor assist <parent-session-id> --task "..." [--claim scope]...
/// [--allow Tool,Tool] [--model m]`: spawn a helper session to finish the
/// parent's job faster. The human names the split (the task and the helper's
/// lease scopes); v1 does no auto-decomposition.
public final class AssistCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static int run(ConductorHome home, String[] args) {
        if (args.length == 0) {
            System.err.println("usage: conductor assist <parent-session-id> --task \"...\" "
                    + "[--claim <scope>]... [--allow Tool,Tool] [--model <m>]");
            return 2;
        }
        String parent = args[0];
        String task = null;
        String model = null;
        List<String> scopes = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--task" -> task = i + 1 < args.length ? args[++i] : null;
                case "--claim" -> { if (i + 1 < args.length) scopes.add(args[++i]); }
                case "--allow" -> { if (i + 1 < args.length) allowed.addAll(List.of(args[++i].split(","))); }
                case "--model" -> model = i + 1 < args.length ? args[++i] : null;
                default -> { }
            }
        }
        if (task == null || task.isBlank()) {
            System.err.println("conductor assist: --task is required (name the helper's slice).");
            return 2;
        }

        var client = new DaemonClient(home);
        var port = client.ensureDaemon();
        if (port.isEmpty()) {
            System.err.println("conductor assist: bus daemon unavailable; cannot spawn a helper.");
            return 1;
        }

        ObjectNode payload = JSON.createObjectNode();
        payload.put("parent", parent);
        payload.put("task", task);
        if (model != null) {
            payload.put("model", model);
        }
        ArrayNode s = payload.putArray("scopes");
        scopes.forEach(s::add);
        ArrayNode a = payload.putArray("allowedTools");
        allowed.forEach(a::add);

        var resp = client.postJson(port.get(), "/api/assist", payload.toString());
        if (resp.isEmpty()) {
            System.err.println("conductor assist: no response from the daemon.");
            return 1;
        }
        try {
            var root = JSON.readTree(resp.get());
            if (!root.path("ok").asBoolean(false)) {
                System.err.println("conductor assist failed: " + root.path("error").asText("unknown"));
                return 1;
            }
            System.out.println("conductor: spawned helper " + shortId(root.path("helper_id").asText()));
            System.out.println("  branch:   " + root.path("branch").asText());
            System.out.println("  worktree: " + root.path("worktree").asText());
            System.out.println("  briefing: " + root.path("briefing").asText());
            System.out.println("  pid:      " + root.path("pid").asLong());
            System.out.println("The helper works in its own worktree and integrates via PR. "
                    + "Watch its progress with `conductor ps` and your `inbox`.");
            return 0;
        } catch (Exception e) {
            System.err.println("conductor assist: bad response (" + e + ")");
            return 1;
        }
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private AssistCommand() {
    }
}
