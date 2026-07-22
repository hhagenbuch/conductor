package io.github.hhagenbuch.conductor.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hhagenbuch.conductor.ConductorHome;
import io.github.hhagenbuch.conductor.Main;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/// The bus daemon: one per machine, owning the SQLite registry behind a
/// localhost-only HTTP API. Singleton is enforced by an exclusive file lock
/// taken BEFORE binding; the port file is written LAST, so a reader that
/// finds a port file finds a daemon that is already serving. Losing the
/// lock race is a normal, silent exit: someone else is the daemon.
public final class Daemon {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void run(ConductorHome home) throws Exception {
        var lockChannel = FileChannel.open(home.lockFile(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = lockChannel.tryLock();
        if (lock == null) {
            System.err.println("conductor daemon already running (lock held); exiting");
            return;
        }

        var registry = new Registry(home.db(), Clock.systemUTC());
        var server = startServer(registry, new InetSocketAddress("127.0.0.1", 0));
        int port = server.getAddress().getPort();
        Files.writeString(home.portFile(), Integer.toString(port));
        System.err.println("conductor daemon listening on 127.0.0.1:" + port);

        var done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(home.portFile());
            } catch (IOException ignored) {
                // best effort; a stale port file is handled by health checks
            }
            server.stop(0);
            done.countDown();
        }));
        done.await();
    }

    /// Creates and starts the bus HTTP server bound to `addr`. Package-visible
    /// so tests can stand up a real daemon in-process without the file lock or
    /// the blocking latch.
    public static HttpServer startServer(Registry registry, InetSocketAddress addr) throws IOException {
        var server = HttpServer.create(addr, 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> handle(registry, exchange));
        server.start();
        return server;
    }

    static void handle(Registry registry, HttpExchange ex) throws IOException {
        try (ex) {
            var path = ex.getRequestURI().getPath();
            var method = ex.getRequestMethod();
            try {
                if (method.equals("GET") && path.equals("/api/health")) {
                    respond(ex, 200, JSON.createObjectNode()
                            .put("ok", true).put("version", Main.VERSION)
                            .put("pid", ProcessHandle.current().pid()));
                } else if (method.equals("POST") && path.startsWith("/api/hook/")) {
                    handleHook(registry, ex, path.substring("/api/hook/".length()));
                } else if (method.equals("GET") && path.equals("/api/sessions")) {
                    respond(ex, 200, sessionsJson(registry, queryParam(ex, "project")));
                } else if (method.equals("GET") && path.equals("/api/peers")) {
                    respond(ex, 200, peersJson(registry, queryParam(ex, "session")));
                } else if (method.equals("POST") && path.equals("/api/messages")) {
                    handlePost(registry, ex);
                } else if (method.equals("GET") && path.equals("/api/inbox")) {
                    handleInbox(registry, ex);
                } else {
                    respond(ex, 404, JSON.createObjectNode().put("error", "not found"));
                }
            } catch (Exception e) {
                respond(ex, 500, JSON.createObjectNode().put("error", String.valueOf(e)));
            }
        }
    }

    /// Hooks forward their raw stdin payloads; all parsing lives here so the
    /// hook scripts stay trivial and fail-open.
    private static void handleHook(Registry registry, HttpExchange ex, String event) throws Exception {
        JsonNode payload = JSON.readTree(ex.getRequestBody());
        var sessionId = text(payload, "session_id");
        if (sessionId == null) {
            respond(ex, 400, JSON.createObjectNode().put("error", "no session_id"));
            return;
        }
        switch (event) {
            case "SessionStart" -> {
                var cwd = text(payload, "cwd");
                var git = GitInfo.of(cwd);
                boolean isChild = "1".equals(ex.getRequestHeaders().getFirst("X-Conductor-Child"));
                registry.register(sessionId, git.repoIdentity(), git.branch(), cwd,
                        text(payload, "transcript_path"), isChild);
            }
            case "UserPromptSubmit", "PostToolUse" -> registry.heartbeat(sessionId);
            case "Stop" -> registry.recordActivity(sessionId, text(payload, "last_assistant_message"));
            case "SessionEnd" -> registry.end(sessionId);
            default -> {
                respond(ex, 400, JSON.createObjectNode().put("error", "unknown event " + event));
                return;
            }
        }
        respond(ex, 200, JSON.createObjectNode().put("ok", true));
    }

    private static void handlePost(Registry registry, HttpExchange ex) throws Exception {
        JsonNode body = JSON.readTree(ex.getRequestBody());
        var delivered = registry.post(text(body, "from"), text(body, "to"), text(body, "body"));
        if (delivered.isEmpty()) {
            respond(ex, 404, JSON.createObjectNode()
                    .put("error", "no unique live session matches '" + text(body, "to") + "'"));
        } else {
            respond(ex, 200, JSON.createObjectNode().put("ok", true).put("to", delivered.get()));
        }
    }

    private static void handleInbox(Registry registry, HttpExchange ex) throws Exception {
        var sessionId = queryParam(ex, "session");
        boolean consume = "true".equals(queryParam(ex, "consume"));
        ArrayNode arr = JSON.createArrayNode();
        for (var m : registry.inbox(sessionId, consume)) {
            arr.add(JSON.createObjectNode()
                    .put("from", m.fromSession()).put("body", m.body()).put("at", m.createdAt()));
        }
        ObjectNode out = JSON.createObjectNode();
        out.set("messages", arr);
        respond(ex, 200, out);
    }

    private static ObjectNode peersJson(Registry registry, String sessionId) throws Exception {
        ArrayNode arr = JSON.createArrayNode();
        for (var s : registry.peersOf(sessionId)) {
            arr.add(sessionNode(s));
        }
        ObjectNode out = JSON.createObjectNode();
        out.set("sessions", arr);
        return out;
    }

    private static ObjectNode sessionsJson(Registry registry, String project) throws Exception {
        ArrayNode arr = JSON.createArrayNode();
        for (var s : registry.sessions(project)) {
            arr.add(sessionNode(s));
        }
        ObjectNode out = JSON.createObjectNode();
        out.set("sessions", arr);
        return out;
    }

    private static ObjectNode sessionNode(Registry.Session s) {
        return JSON.createObjectNode()
                    .put("session_id", s.sessionId())
                    .put("project_dir", s.projectDir())
                    .put("git_branch", s.gitBranch())
                    .put("worktree", s.worktree())
                    .put("stated_task", s.statedTask())
                    .put("started_at", s.startedAt())
                    .put("last_seen", s.lastSeen())
                    .put("is_child", s.isChild())
                    .put("observed", s.observed())
                    .put("last_activity", s.lastActivity())
                    .put("status", s.status());
    }

    private static String text(JsonNode node, String field) {
        var v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String queryParam(HttpExchange ex, String name) {
        var q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (var pair : q.split("&")) {
            var i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void respond(HttpExchange ex, int code, JsonNode body) throws IOException {
        var bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private Daemon() { }
}
