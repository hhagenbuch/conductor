package io.github.hhagenbuch.conductor.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.conductor.ConductorHome;
import io.github.hhagenbuch.conductor.shim.DaemonClient;

/// `conductor ps`: show sessions known to the bus. Reads only; if the daemon
/// is down it says so loudly (leases unenforced) and reports nothing rather
/// than starting a daemon just to answer a question.
public final class PsCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static int run(ConductorHome home) throws Exception {
        var client = new DaemonClient(home);
        var port = client.livePort();
        if (port.isEmpty()) {
            System.out.println("conductor: bus daemon is not running.");
            System.out.println("Leases are UNENFORCED while the daemon is down. Sessions will");
            System.out.println("register (and a daemon will start) on their next bus call.");
            return 0;
        }
        var body = client.get(port.get(), "/api/sessions").orElse("{}");
        JsonNode root = JSON.readTree(body);
        var sessions = root.path("sessions");
        if (sessions.isEmpty()) {
            System.out.println("No sessions registered.");
            return 0;
        }
        System.out.printf("%-10s %-8s %-22s %-16s %s%n",
                "SESSION", "STATUS", "PROJECT", "BRANCH", "SEEN");
        for (var s : sessions) {
            var flags = new StringBuilder();
            if (s.path("is_child").asBoolean(false)) {
                flags.append(" child");
            }
            if (s.path("observed").asBoolean(false)) {
                flags.append(" observed");
            }
            System.out.printf("%-10s %-8s %-22s %-16s %s%s%n",
                    shortId(s.path("session_id").asText()),
                    s.path("status").asText(),
                    tail(s.path("project_dir").asText(""), 22),
                    s.path("git_branch").asText("?"),
                    ageOf(s.path("last_seen").asLong()) + " ago",
                    flags);
            var act = s.path("last_activity");
            if (!act.isNull() && !act.asText("").isBlank()) {
                System.out.println("    last: " + act.asText());
            }
        }
        printLeases(client, port.get());
        printFlock(client, port.get());
        return 0;
    }

    /// One-line impact-awareness banner, shown only when Flock is enabled, so a
    /// silent inbox is legible: "reachable" means silence is "no impact",
    /// "UNREACHABLE" means silence is "not watching".
    private static void printFlock(DaemonClient client, int port) throws Exception {
        var body = client.get(port, "/api/flock/status").orElse("{}");
        var status = JSON.readTree(body);
        if (!status.path("enabled").asBoolean(false)) {
            return;
        }
        boolean reachable = status.path("fathom_reachable").asBoolean(false);
        System.out.println();
        System.out.println("flock (impact awareness): ON, fathom "
                + (reachable ? "reachable" : "UNREACHABLE (alerts off; leases still enforced)"));
    }

    private static void printLeases(DaemonClient client, int port) throws Exception {
        var body = client.get(port, "/api/leases").orElse("{}");
        var leases = JSON.readTree(body).path("leases");
        if (leases.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.printf("%-6s %-28s %-10s %s%n", "LEASE", "SCOPE", "HOLDER", "EXPIRES");
        for (var l : leases) {
            System.out.printf("#%-5d %-28s %-10s in %s%n",
                    l.path("id").asLong(),
                    l.path("scope").asText(),
                    shortId(l.path("session_id").asText()),
                    minsUntil(l.path("expires_at").asLong()));
        }
    }

    private static String minsUntil(long epochMillis) {
        long m = Math.max(0, (epochMillis - System.currentTimeMillis()) / 60_000);
        return m + "m";
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : "…" + s.substring(s.length() - n + 1);
    }

    private static String ageOf(long epochMillis) {
        long sec = Math.max(0, (System.currentTimeMillis() - epochMillis) / 1000);
        if (sec < 60) {
            return sec + "s";
        }
        if (sec < 3600) {
            return (sec / 60) + "m";
        }
        return (sec / 3600) + "h";
    }

    private PsCommand() { }
}
