package io.github.hhagenbuch.conductor.flock;

import io.github.hhagenbuch.conductor.ConductorHome;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

/// Flock's opt-in configuration, read from `~/.conductor/flock.properties`
/// (env vars override, for CI and tests). Impact awareness ships **off**: an
/// absent or `enabled=false` config means Flock never runs, exactly like
/// transcript observation is off until a project opts in. Reading config never
/// throws ... a malformed or missing file yields a disabled config, never a
/// daemon that fails to start.
///
/// Keys:
///   enabled          true to turn Flock on (default false)
///   fathom_cmd       the command that launches `fathom serve` as a stdio MCP
///                    server, whitespace-separated, e.g.
///                    `java -jar /abs/fathom.jar serve --config /abs/fathom.yaml`
///   throttle_minutes minutes between repeat alerts for one (source,entity,
///                    consumer) triple (default 10)
///   additive         true to also surface COMPAT (additive) changes, off by
///                    default so only BREAKING changes alert (default false)
public record FlockConfig(boolean enabled, List<String> fathomCommand,
                          long throttleMinutes, boolean additive) {

    public static final long DEFAULT_THROTTLE_MINUTES = 10;

    /// A disabled config: Flock is a no-op.
    public static FlockConfig disabled() {
        return new FlockConfig(false, List.of(), DEFAULT_THROTTLE_MINUTES, false);
    }

    public static FlockConfig load(ConductorHome home) {
        var props = new Properties();
        var file = home.flockConfig();
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {
                // an unreadable config disables Flock; it never blocks the daemon
                return disabled();
            }
        }

        boolean enabled = boolValue("CONDUCTOR_FLOCK_ENABLED", props.getProperty("enabled"), false);
        if (!enabled) {
            return disabled();
        }
        var cmd = tokens(envOr("CONDUCTOR_FLOCK_FATHOM_CMD", props.getProperty("fathom_cmd")));
        long throttle = longValue(props.getProperty("throttle_minutes"), DEFAULT_THROTTLE_MINUTES);
        boolean additive = boolValue("CONDUCTOR_FLOCK_ADDITIVE", props.getProperty("additive"), false);
        // Enabled but no fathom command means we cannot reach the graph; treat
        // it as configured-but-unreachable rather than crashing. The engine
        // degrades (ps says fathom is unreachable) and everything else works.
        return new FlockConfig(true, cmd, throttle, additive);
    }

    /// True when Flock is on and a fathom launch command is configured. The
    /// engine still degrades if the command fails to start; this only gates
    /// whether it is worth trying.
    public boolean runnable() {
        return enabled && !fathomCommand.isEmpty();
    }

    public long throttleMillis() {
        return throttleMinutes * 60_000;
    }

    private static String envOr(String envKey, String fileValue) {
        var env = System.getenv(envKey);
        return env != null && !env.isBlank() ? env : fileValue;
    }

    private static boolean boolValue(String envKey, String fileValue, boolean dflt) {
        var v = envOr(envKey, fileValue);
        return v == null ? dflt : v.trim().equalsIgnoreCase("true");
    }

    private static long longValue(String fileValue, long dflt) {
        if (fileValue == null || fileValue.isBlank()) {
            return dflt;
        }
        try {
            return Long.parseLong(fileValue.trim());
        } catch (NumberFormatException bad) {
            return dflt;
        }
    }

    private static List<String> tokens(String cmd) {
        if (cmd == null || cmd.isBlank()) {
            return List.of();
        }
        return List.of(cmd.trim().split("\\s+"));
    }
}
