package io.github.hhagenbuch.conductor.shim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.conductor.ConductorHome;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/// The per-session stdio MCP server. Claude Code spawns one of these per
/// session (proven per-session, GROUND-TRUTH § 4.2), so it is the natural
/// place to lazily start the daemon and to know "which session am I": the
/// session id is inherited via CLAUDE_CODE_SESSION_ID (LIVE-verified that MCP
/// server processes receive it).
///
/// Newline-delimited JSON-RPC 2.0. Every tool degrades gracefully: if the
/// daemon is unreachable the tool returns a "bus unavailable" text result,
/// never an error that would unmount the surface.
public final class McpShim {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL = "2025-06-18";

    private final ConductorHome home;
    private final DaemonClient client;
    private final String sessionId;
    private final PrintStream out;

    McpShim(ConductorHome home, DaemonClient client, String sessionId, PrintStream out) {
        this.home = home;
        this.client = client;
        this.sessionId = sessionId;
        this.out = out;
    }

    public static void run(ConductorHome home) throws Exception {
        var sessionId = System.getenv("CLAUDE_CODE_SESSION_ID");
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        var shim = new McpShim(home, new DaemonClient(home), sessionId, out);
        shim.loop(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    public void loop(BufferedReader in) throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode msg = JSON.readTree(line);
            var method = msg.path("method").asText(null);
            var hasId = msg.has("id") && !msg.get("id").isNull();
            if (method == null) {
                continue;
            }
            switch (method) {
                case "initialize" -> reply(msg, initializeResult());
                case "notifications/initialized" -> { /* no response to a notification */ }
                case "tools/list" -> reply(msg, toolsList());
                case "tools/call" -> reply(msg, callTool(msg.path("params")));
                case "ping" -> reply(msg, JSON.createObjectNode());
                default -> {
                    if (hasId) {
                        replyError(msg, -32601, "method not found: " + method);
                    }
                }
            }
        }
    }

    private ObjectNode initializeResult() {
        var r = JSON.createObjectNode();
        r.put("protocolVersion", PROTOCOL);
        r.putObject("capabilities").putObject("tools");
        r.putObject("serverInfo").put("name", "conductor").put("version", "0.1.0");
        return r;
    }

    private ObjectNode toolsList() {
        var tools = JSON.createArrayNode();
        tools.add(tool("who_else",
                "List other Claude Code sessions working on this project (and their freshness), so you can coordinate instead of colliding.",
                schema(false)));
        tools.add(tool("post",
                "Send a short message to another session's inbox. Address it by session id or an unambiguous id prefix.",
                postSchema()));
        tools.add(tool("inbox",
                "Read messages other sessions have sent you. Check it at natural boundaries: task start, before commits or PRs, and when you are blocked.",
                schema(false)));
        var r = JSON.createObjectNode();
        r.set("tools", tools);
        return r;
    }

    private ObjectNode callTool(JsonNode params) {
        var name = params.path("name").asText("");
        var args = params.path("arguments");
        if (sessionId == null) {
            return textResult("conductor: no session id in environment; cannot use the bus from this session.", true);
        }
        var portOpt = client.ensureDaemon();
        if (portOpt.isEmpty()) {
            return textResult("conductor bus unavailable (daemon not reachable). Leases are unenforced and messages cannot be sent right now; proceed, but coordinate manually.", true);
        }
        int port = portOpt.get();
        try {
            return switch (name) {
                case "who_else" -> whoElse(port);
                case "post" -> post(port, args);
                case "inbox" -> inbox(port);
                default -> textResult("conductor: unknown tool " + name, true);
            };
        } catch (Exception e) {
            return textResult("conductor: bus call failed (" + e + ")", true);
        }
    }

    private ObjectNode whoElse(int port) throws Exception {
        // The daemon resolves "my project" from my registration, so the shim
        // never recomputes git identity locally.
        var body = client.get(port, "/api/peers?session=" + enc(sessionId)).orElse("{}");
        var root = JSON.readTree(body);
        var sb = new StringBuilder();
        int others = 0;
        for (var s : root.path("sessions")) {
            others++;
            sb.append("• ").append(shortId(s.path("session_id").asText()))
              .append("  [").append(s.path("status").asText()).append("]")
              .append("  branch ").append(s.path("git_branch").asText("?"))
              .append("  seen ").append(ageOf(s.path("last_seen").asLong())).append(" ago");
            var task = s.path("stated_task");
            if (!task.isNull() && !task.asText("").isBlank()) {
                sb.append("\n    task: ").append(task.asText());
            }
            var act = s.path("last_activity");
            if (!act.isNull() && !act.asText("").isBlank()) {
                sb.append("\n    last: ").append(act.asText());
            }
            sb.append('\n');
        }
        if (others == 0) {
            return textResult("No other sessions on this project. You are working alone here.", false);
        }
        return textResult(others + " other session(s) on this project:\n" + sb, false);
    }

    private ObjectNode post(int port, JsonNode args) throws Exception {
        var to = args.path("to").asText("");
        var message = args.path("message").asText("");
        if (to.isBlank() || message.isBlank()) {
            return textResult("post needs 'to' (session id or prefix) and 'message'.", true);
        }
        var payload = JSON.createObjectNode()
                .put("from", sessionId).put("to", to).put("body", message);
        var resp = client.postJson(port, "/api/messages", payload.toString());
        if (resp.isEmpty()) {
            return textResult("conductor bus unavailable; message not sent.", true);
        }
        var root = JSON.readTree(resp.get());
        if (root.path("ok").asBoolean(false)) {
            return textResult("Delivered to " + shortId(root.path("to").asText()) + ".", false);
        }
        return textResult(root.path("error").asText("could not deliver message"), true);
    }

    private ObjectNode inbox(int port) throws Exception {
        var resp = client.get(port, "/api/inbox?session=" + enc(sessionId) + "&consume=true");
        if (resp.isEmpty()) {
            return textResult("conductor bus unavailable; cannot read inbox.", true);
        }
        var root = JSON.readTree(resp.get());
        var msgs = root.path("messages");
        if (msgs.isEmpty()) {
            return textResult("Inbox empty.", false);
        }
        var sb = new StringBuilder(msgs.size() + " message(s):\n");
        for (var m : msgs) {
            sb.append("• from ").append(shortId(m.path("from").asText()))
              .append(" (").append(ageOf(m.path("at").asLong())).append(" ago): ")
              .append(m.path("body").asText()).append('\n');
        }
        return textResult(sb.toString(), false);
    }

    // ---- MCP encoding helpers ----

    private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        var t = JSON.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        t.set("inputSchema", inputSchema);
        return t;
    }

    private ObjectNode schema(boolean unused) {
        var s = JSON.createObjectNode();
        s.put("type", "object");
        s.putObject("properties");
        return s;
    }

    private ObjectNode postSchema() {
        var s = JSON.createObjectNode();
        s.put("type", "object");
        var props = s.putObject("properties");
        props.putObject("to").put("type", "string")
                .put("description", "Recipient session id or unambiguous id prefix.");
        props.putObject("message").put("type", "string")
                .put("description", "The message body.");
        s.putArray("required").add("to").add("message");
        return s;
    }

    private ObjectNode textResult(String text, boolean isError) {
        var r = JSON.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        r.put("isError", isError);
        return r;
    }

    private void reply(JsonNode request, JsonNode result) {
        if (!request.has("id") || request.get("id").isNull()) {
            return;
        }
        var env = JSON.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.set("id", request.get("id"));
        env.set("result", result);
        out.println(env);
    }

    private void replyError(JsonNode request, int code, String message) {
        var env = JSON.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.set("id", request.get("id"));
        var err = env.putObject("error");
        err.put("code", code);
        err.put("message", message);
        out.println(env);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String ageOf(long epochMillis) {
        long s = Math.max(0, (System.currentTimeMillis() - epochMillis) / 1000);
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return (s / 60) + "m";
        }
        return (s / 3600) + "h";
    }
}
