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
        return 0;
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
