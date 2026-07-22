package io.github.hhagenbuch.conductor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.conductor.daemon.Daemon;
import io.github.hhagenbuch.conductor.daemon.Registry;
import io.github.hhagenbuch.conductor.shim.DaemonClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// The Phase 2 acceptance test: a real bus, one session claims a path, a second
/// session's conflicting Write is denied with a helpful message ... and, when
/// the daemon is down, enforcement fails OPEN.
class EnforcementE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void conflictingWriteIsBlockedWithHelpfulReason(@TempDir Path tmp) throws Exception {
        var home = ConductorHome.of(tmp.resolve(".conductor"));
        try (var registry = new Registry(home.db(), Clock.systemUTC())) {
            var server = Daemon.startServer(registry, new InetSocketAddress("127.0.0.1", 0));
            int port = server.getAddress().getPort();
            Files.writeString(home.portFile(), Integer.toString(port));
            try {
                var client = new DaemonClient(home, Duration.ofSeconds(2));
                var repo = initGitRepo(tmp.resolve("demo")).toString();

                // Two sessions register on one project (real git dir -> branch main).
                register(client, port, "holder-1", repo);
                register(client, port, "worker-2", repo);

                // holder-1 claims the source tree.
                var claim = client.postJson(port, "/api/lease/claim",
                        "{\"session\":\"holder-1\",\"scope\":\"path:src/main/**\",\"note\":\"refactoring the parser\"}")
                        .orElseThrow();
                assertTrue(JSON.readTree(claim).path("ok").asBoolean());

                // worker-2 tries to edit a file under that scope -> DENY.
                var deny = enforce(client, port, "worker-2", repo, "Write",
                        "{\"file_path\":\"" + repo + "/src/main/Parser.java\",\"content\":\"x\"}");
                assertEquals("deny",
                        deny.path("hookSpecificOutput").path("permissionDecision").asText());
                var reason = deny.path("hookSpecificOutput").path("permissionDecisionReason").asText();
                assertTrue(reason.contains("LEASE CONFLICT"), reason);
                assertTrue(reason.contains("holder-1"), reason);
                assertTrue(reason.contains("refactoring the parser"), reason);

                // worker-2 editing a DISJOINT file -> allowed.
                var ok = enforce(client, port, "worker-2", repo, "Write",
                        "{\"file_path\":\"" + repo + "/src/test/ParserTest.java\",\"content\":\"x\"}");
                assertFalse(ok.path("block").asBoolean(true), "disjoint write must be allowed");

                // holder-1 editing its OWN leased file -> allowed (you never block yourself).
                var self = enforce(client, port, "holder-1", repo, "Write",
                        "{\"file_path\":\"" + repo + "/src/main/Parser.java\",\"content\":\"x\"}");
                assertFalse(self.path("block").asBoolean(true), "holder is not blocked by its own lease");

                // A history-moving git command from worker-2: not blocked by a
                // path lease (best-effort scope), but WOULD be by a branch lease.
                client.postJson(port, "/api/lease/claim",
                        "{\"session\":\"holder-1\",\"scope\":\"branch:main\"}");
                var gitDeny = enforce(client, port, "worker-2", repo, "Bash",
                        "{\"command\":\"git push origin main\"}");
                assertEquals("deny",
                        gitDeny.path("hookSpecificOutput").path("permissionDecision").asText(),
                        "git push on a leased branch is blocked");
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void enforcementFailsOpenWhenDaemonIsDown(@TempDir Path tmp) throws Exception {
        var home = ConductorHome.of(tmp.resolve(".conductor"));
        // Stand a daemon up, claim a lease, prove the block, then KILL the
        // daemon and prove the same call now fails open.
        var registry = new Registry(home.db(), Clock.systemUTC());
        var server = Daemon.startServer(registry, new InetSocketAddress("127.0.0.1", 0));
        int port = server.getAddress().getPort();
        Files.writeString(home.portFile(), Integer.toString(port));
        var client = new DaemonClient(home, Duration.ofMillis(500));
        var repo = initGitRepo(tmp.resolve("failopen")).toString();
        register(client, port, "holder-1", repo);
        register(client, port, "worker-2", repo);
        client.postJson(port, "/api/lease/claim",
                "{\"session\":\"holder-1\",\"scope\":\"repo:\"}");
        var blocked = enforce(client, port, "worker-2", repo, "Write",
                "{\"file_path\":\"" + repo + "/x.txt\",\"content\":\"x\"}");
        assertEquals("deny", blocked.path("hookSpecificOutput").path("permissionDecision").asText());

        // Daemon dies.
        server.stop(0);
        registry.close();

        // The hook's contract: an enforce call to a dead daemon yields nothing,
        // so the shell hook exits 0 and the tool proceeds. We assert the client
        // returns empty (the shell script's `[ -z "$RESP" ] && exit 0` path).
        var resp = client.postJson(port, "/api/enforce",
                "{\"session_id\":\"worker-2\",\"cwd\":\"" + repo + "\",\"tool_name\":\"Write\","
                + "\"tool_input\":{\"file_path\":\"" + repo + "/x.txt\"}}");
        assertTrue(resp.isEmpty(), "a dead daemon returns no decision, so enforcement fails open");
    }

    /// A real git repo on branch `main`, path canonicalized (realpath) so it
    /// matches the daemon's git-common-dir identity exactly ... no /var vs
    /// /private/var symlink mismatch on macOS.
    private Path initGitRepo(Path dir) throws Exception {
        Files.createDirectories(dir);
        git(dir, "init", "-q");
        git(dir, "checkout", "-q", "-b", "main");
        git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init", "--allow-empty");
        return dir.toRealPath();
    }

    private void git(Path dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
    }

    private void register(DaemonClient client, int port, String id, String repo) {
        client.postJson(port, "/api/hook/SessionStart",
                "{\"session_id\":\"" + id + "\",\"cwd\":\"" + repo + "\"}", null);
    }

    private JsonNode enforce(DaemonClient client, int port, String session, String repo,
                             String tool, String toolInput) throws Exception {
        var body = "{\"session_id\":\"" + session + "\",\"cwd\":\"" + repo + "\","
                + "\"tool_name\":\"" + tool + "\",\"tool_input\":" + toolInput + "}";
        return JSON.readTree(client.postJson(port, "/api/enforce", body).orElseThrow());
    }
}
