package io.github.hhagenbuch.conductor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.conductor.ConductorHome;
import io.github.hhagenbuch.conductor.flock.FlockConfig;
import io.github.hhagenbuch.conductor.shim.DaemonClient;

/// `conductor flock`: report impact-awareness status ... whether Flock is
/// enabled, whether the fathom graph is reachable, and the current tuning. This
/// is how a human answers "is a silent inbox 'no impact' or 'not watching'?"
/// (the same distinction `ps` surfaces in its banner).
public final class FlockCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static int run(ConductorHome home) throws Exception {
        var config = FlockConfig.load(home);
        System.out.println("conductor flock (impact awareness)");
        System.out.println("  enabled:  " + config.enabled());
        if (!config.enabled()) {
            System.out.println();
            System.out.println("Flock is off. Turn it on in " + home.flockConfig() + ":");
            System.out.println("  enabled=true");
            System.out.println("  fathom_cmd=java -jar /abs/path/to/fathom.jar serve --config /abs/fathom.yaml");
            System.out.println("  throttle_minutes=" + FlockConfig.DEFAULT_THROTTLE_MINUTES);
            System.out.println("  additive=false");
            return 0;
        }
        System.out.println("  fathom cmd: " + String.join(" ", config.fathomCommand()));
        System.out.println("  throttle:   " + config.throttleMinutes() + "m per (source,entity,consumer)");
        System.out.println("  additive:   " + config.additive() + " (COMPAT changes "
                + (config.additive() ? "also alert" : "silent") + ")");

        var client = new DaemonClient(home);
        var port = client.livePort();
        if (port.isEmpty()) {
            System.out.println("  daemon:     down (start a session or run `conductor daemon`)");
            return 0;
        }
        var body = client.get(port.get(), "/api/flock/status").orElse("{}");
        var root = JSON.readTree(body);
        boolean reachable = root.path("fathom_reachable").asBoolean(false);
        System.out.println("  fathom:     " + (reachable ? "reachable" : "UNREACHABLE (impact awareness "
                + "silently disabled; leases and messaging still work)"));
        return 0;
    }

    private FlockCommand() {
    }
}
