package io.github.hhagenbuch.conductor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.conductor.daemon.Daemon;
import io.github.hhagenbuch.conductor.daemon.Registry;
import io.github.hhagenbuch.conductor.shim.DaemonClient;
import io.github.hhagenbuch.conductor.shim.McpShim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/// The Phase 1 acceptance test in miniature: a real bus daemon, two sessions
/// registered through the daemon's hook API, and the MCP shim answering
/// who_else / post / inbox against it. No Claude Code process needed ... this
/// exercises exactly the wire the hooks and shim use at runtime.
class EndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void twoSessionsSeeEachOtherAndExchangeMail(@TempDir Path tmp) throws Exception {
        var home = ConductorHome.of(tmp.resolve(".conductor"));
        try (var registry = new Registry(home.db(), Clock.systemUTC())) {
            var server = Daemon.startServer(registry, new InetSocketAddress("127.0.0.1", 0));
            int port = server.getAddress().getPort();
            Files.writeString(home.portFile(), Integer.toString(port));
            try {
                var client = new DaemonClient(home, Duration.ofSeconds(2));

                // Two sessions register (as their SessionStart hooks would),
                // sharing one project identity.
                hook(client, port, "SessionStart", sessionStartPayload("sess-alpha", "/repo/x"));
                hook(client, port, "SessionStart", sessionStartPayload("sess-beta", "/repo/x"));

                // A third session on a DIFFERENT project must not appear as a peer.
                hook(client, port, "SessionStart", sessionStartPayload("sess-gamma", "/repo/other"));

                // Alpha's shim asks who else is here.
                var who = callTool(home, client, "sess-alpha", "who_else", "{}");
                assertTrue(who.contains("sess-bet"), "alpha should see beta: " + who);
                assertFalse(who.contains("sess-gam"), "gamma is on another project, not a peer");

                // Alpha messages beta by prefix.
                var sent = callTool(home, client, "sess-alpha", "post",
                        "{\"to\":\"sess-beta\",\"message\":\"I've got the controller; take the tests\"}");
                assertTrue(sent.toLowerCase().contains("delivered"), "post should confirm: " + sent);

                // Beta reads its inbox.
                var inbox = callTool(home, client, "sess-beta", "inbox", "{}");
                assertTrue(inbox.contains("take the tests"), "beta should receive the message: " + inbox);
                assertTrue(inbox.contains("sess-alp"), "message should name the sender: " + inbox);

                // Consumed: a second read is empty.
                var inbox2 = callTool(home, client, "sess-beta", "inbox", "{}");
                assertTrue(inbox2.contains("empty"), "inbox should be consumed: " + inbox2);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void shimDegradesGracefullyWhenBusIsDown(@TempDir Path tmp) throws Exception {
        var home = ConductorHome.of(tmp.resolve(".conductor"));
        // No daemon, no port file, and a client that cannot start one (bad jar
        // discovery in a test JVM) ... the tool must return a helpful message,
        // never throw.
        var client = new DaemonClient(home, Duration.ofMillis(200)) {
            @Override public java.util.Optional<Integer> ensureDaemon() {
                return java.util.Optional.empty();
            }
        };
        var out = callTool(home, client, "sess-solo", "who_else", "{}");
        assertTrue(out.toLowerCase().contains("unavailable"),
                "a down bus degrades to a message, not an exception: " + out);
    }

    // ---- helpers that speak the same wire the hook script and shim use ----

    private void hook(DaemonClient client, int port, String event, String payload) {
        var resp = client.postJson(port, "/api/hook/" + event, payload, null);
        assertTrue(resp.isPresent(), "hook post should reach the daemon");
    }

    private String sessionStartPayload(String sessionId, String cwd) {
        // cwd here is not a real git dir; GitInfo falls back to the canonical
        // path as identity, which is stable and sufficient for the test.
        return "{\"session_id\":\"" + sessionId + "\",\"cwd\":\"" + cwd
                + "\",\"transcript_path\":\"/t/" + sessionId + ".jsonl\"}";
    }

    /// Drive a single tools/call through a real McpShim instance and return
    /// the text of its result.
    private String callTool(ConductorHome home, DaemonClient client, String sessionId,
                            String tool, String argsJson) throws Exception {
        var buf = new ByteArrayOutputStream();
        var out = new PrintStream(buf, true, StandardCharsets.UTF_8);

        Constructor<McpShim> ctor = McpShim.class.getDeclaredConstructor(
                ConductorHome.class, DaemonClient.class, String.class, PrintStream.class);
        ctor.setAccessible(true);
        McpShim shim = ctor.newInstance(home, client, sessionId, out);

        var req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}}";
        shim.loop(new BufferedReader(new StringReader(req)));

        JsonNode resp = JSON.readTree(buf.toString(StandardCharsets.UTF_8).trim());
        return resp.path("result").path("content").get(0).path("text").asText();
    }
}
