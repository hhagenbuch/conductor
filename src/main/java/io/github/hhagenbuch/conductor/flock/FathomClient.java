package io.github.hhagenbuch.conductor.flock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Client to a `fathom serve` MCP endpoint. fathom's server is **stdio**, not
/// HTTP (it is mounted the same way Claude Code mounts conductor's own shim),
/// so this spawns the configured `fathom serve` command as a long-lived child
/// process and speaks newline-delimited JSON-RPC 2.0 over its stdin/stdout.
///
/// It consumes each tool's `structuredContent` (fathom emits both a human text
/// block and a structured object; Flock uses the structured object so it never
/// parses prose). Every call is fail-open: an unstarted/dead process, a
/// timeout, or a malformed reply yields an empty result, and the engine treats
/// that as "impact awareness unavailable" ... never an error that stops work.
///
/// The endpoint is stateless per query but the process is reused across
/// evaluations. All calls are serialized (one request in flight on the pipe at
/// a time); the daemon fires Flock evaluations off a single background path, so
/// this is not a throughput bottleneck.
public final class FathomClient implements FathomGraph {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL = "2025-06-18";

    private final List<String> command;
    private final Duration callTimeout;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "flock-fathom-reader");
        t.setDaemon(true);
        return t;
    });

    private Process process;
    private BufferedWriter toFathom;
    private BufferedReader fromFathom;
    private int nextId = 1;
    private boolean handshakeOk;

    public FathomClient(List<String> command) {
        this(command, Duration.ofSeconds(5));
    }

    public FathomClient(List<String> command, Duration callTimeout) {
        this.command = List.copyOf(command);
        this.callTimeout = callTimeout;
    }

    /// Reverse-map a source file to the graph entities it backs. Empty if the
    /// file is not indexed or fathom is unreachable.
    @Override
    public synchronized Entities resolveFile(String repo, String path) {
        var args = JSON.createObjectNode();
        args.put("repo", repo);
        args.put("path", path);
        var structured = callTool("resolve_file", args);
        if (structured.isEmpty()) {
            return Entities.EMPTY;
        }
        var ids = new ArrayList<String>();
        structured.get().path("entities").forEach(n -> ids.add(n.asText()));
        return new Entities(ids);
    }

    /// Cross-repo consumers of a changed entity, tagged with whether the changed
    /// entity is a contract surface. Empty if none or fathom is unreachable.
    @Override
    public synchronized Impact impactedBy(String entityId) {
        var args = JSON.createObjectNode();
        args.put("id", entityId);
        var structured = callTool("impacted_by", args);
        if (structured.isEmpty()) {
            return Impact.EMPTY;
        }
        var node = structured.get();
        var consumers = new ArrayList<Consumer>();
        for (var c : node.path("consumers")) {
            consumers.add(new Consumer(
                    c.path("repo").asText(null),
                    c.path("entity").asText(null),
                    c.path("edgeKind").asText(null),
                    c.path("surface").asBoolean(false)));
        }
        return new Impact(
                node.path("root").asText(entityId),
                node.path("surface").asBoolean(false),
                node.path("surfaceKind").isNull() ? null : node.path("surfaceKind").asText(null),
                consumers);
    }

    /// True if fathom is reachable (a `ping` round-trips). Used by `ps` to tell
    /// a "no impact" silence apart from a "not watching" one.
    @Override
    public synchronized boolean reachable() {
        if (!ensureStarted()) {
            return false;
        }
        var resp = request("ping", JSON.createObjectNode());
        return resp.isPresent();
    }

    // ---- transport ----

    /// Send one `tools/call` and return its `structuredContent` object, or empty
    /// on any failure (fathom down, timeout, error result, no structured field).
    private Optional<JsonNode> callTool(String tool, ObjectNode arguments) {
        if (!ensureStarted()) {
            return Optional.empty();
        }
        var params = JSON.createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments);
        var resp = request("tools/call", params);
        if (resp.isEmpty()) {
            return Optional.empty();
        }
        var result = resp.get().path("result");
        if (result.path("isError").asBoolean(false)) {
            return Optional.empty();
        }
        var structured = result.get("structuredContent");
        return structured != null && structured.isObject() ? Optional.of(structured) : Optional.empty();
    }

    /// Lazily start the fathom process and complete the MCP handshake. Restarts
    /// a dead process. Returns false if it cannot be brought up (Flock then
    /// silently degrades).
    private boolean ensureStarted() {
        if (command.isEmpty()) {
            return false;
        }
        if (process != null && process.isAlive() && handshakeOk) {
            return true;
        }
        teardownProcess();
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false); // keep fathom's stderr off our JSON-RPC stream
            process = pb.start();
            toFathom = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            fromFathom = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            var init = JSON.createObjectNode();
            init.put("protocolVersion", PROTOCOL);
            init.putObject("capabilities");
            init.putObject("clientInfo").put("name", "conductor-flock").put("version", "0.1.0");
            if (request("initialize", init).isEmpty()) {
                teardownProcess();
                return false;
            }
            notifyInitialized();
            handshakeOk = true;
            return true;
        } catch (Exception cannotStart) {
            teardownProcess();
            return false;
        }
    }

    /// One JSON-RPC request/response round trip with a bounded read. On timeout
    /// or I/O error the process is torn down so the next call restarts it.
    private Optional<JsonNode> request(String method, JsonNode params) {
        int id = nextId++;
        var env = JSON.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.put("id", id);
        env.put("method", method);
        env.set("params", params);
        try {
            toFathom.write(env.toString());
            toFathom.write("\n");
            toFathom.flush();
            return Optional.of(readReply(id));
        } catch (TimeoutException | InterruptedException | java.io.IOException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            teardownProcess();
            return Optional.empty();
        } catch (Exception e) {
            teardownProcess();
            return Optional.empty();
        }
    }

    /// Read lines until the JSON-RPC reply with our id arrives, skipping any
    /// notifications. Bounded by callTimeout via the reader thread.
    private JsonNode readReply(int id) throws Exception {
        long deadline = System.nanoTime() + callTimeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("fathom read timed out");
            }
            Future<String> line = reader.submit(fromFathom::readLine);
            String raw;
            try {
                raw = line.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException t) {
                line.cancel(true);
                throw t;
            }
            if (raw == null) {
                throw new java.io.EOFException("fathom stream closed");
            }
            if (raw.isBlank()) {
                continue;
            }
            JsonNode msg = JSON.readTree(raw);
            if (msg.has("id") && msg.get("id").asInt() == id) {
                return msg;
            }
            // otherwise a notification or a stray line; keep reading
        }
    }

    private void notifyInitialized() throws java.io.IOException {
        var note = JSON.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/initialized");
        toFathom.write(note.toString());
        toFathom.write("\n");
        toFathom.flush();
    }

    private void teardownProcess() {
        handshakeOk = false;
        if (process != null) {
            process.destroy();
        }
        process = null;
        toFathom = null;
        fromFathom = null;
    }

    @Override
    public synchronized void close() {
        teardownProcess();
        reader.shutdownNow();
    }
}
