package io.github.hhagenbuch.conductor.shim;

import io.github.hhagenbuch.conductor.ConductorHome;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;

/// Thin HTTP client to the bus daemon, with the two behaviors the whole
/// fail-open story rests on:
///
///  - It discovers the daemon via the port file, and lazily STARTS the
///    daemon (as a detached `conductor daemon` process) if none is running.
///    The daemon's own file lock makes concurrent starts safe: losers exit.
///  - Every call has a short timeout and returns an Optional; the daemon
///    being down is never an exception thrown at the caller. Hooks and the
///    shim translate an empty result into "bus unavailable", never a block.
public class DaemonClient {

    private final ConductorHome home;
    private final HttpClient http;
    private final Duration timeout;

    public DaemonClient(ConductorHome home) {
        this(home, Duration.ofMillis(800));
    }

    public DaemonClient(ConductorHome home, Duration timeout) {
        this.home = home;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public Optional<Integer> port() {
        try {
            if (!Files.exists(home.portFile())) {
                return Optional.empty();
            }
            return Optional.of(Integer.parseInt(Files.readString(home.portFile()).trim()));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /// A live daemon on the recorded port, or empty. Confirms with /health so
    /// a stale port file (daemon crashed) reads as "down", not "up".
    public Optional<Integer> livePort() {
        var p = port();
        if (p.isEmpty()) {
            return Optional.empty();
        }
        return get(p.get(), "/api/health").isPresent() ? p : Optional.empty();
    }

    /// Ensure a daemon is running, starting one if needed, and return its
    /// live port. Bounded wait; returns empty if it never comes up.
    public Optional<Integer> ensureDaemon() {
        var live = livePort();
        if (live.isPresent()) {
            return live;
        }
        startDaemon();
        for (int i = 0; i < 20; i++) {
            sleep(100);
            var p = livePort();
            if (p.isPresent()) {
                return p;
            }
        }
        return Optional.empty();
    }

    private void startDaemon() {
        try {
            var self = jarPath();
            var pb = new ProcessBuilder("java", "-jar", self, "daemon");
            pb.redirectOutput(home.logFile().toFile());
            pb.redirectError(home.logFile().toFile());
            pb.start();
        } catch (Exception e) {
            // Best effort. If we cannot spawn, ensureDaemon returns empty and
            // the caller degrades. A dead coordinator must not stop work.
        }
    }

    private static String jarPath() {
        return new java.io.File(DaemonClient.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath()).getAbsolutePath();
    }

    public Optional<String> get(int port, String path) {
        return send(HttpRequest.newBuilder(URI.create(base(port) + path)).GET());
    }

    public Optional<String> postJson(int port, String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(base(port) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    public Optional<String> postJson(int port, String path, String json, String childHeader) {
        var b = HttpRequest.newBuilder(URI.create(base(port) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (childHeader != null) {
            b.header("X-Conductor-Child", childHeader);
        }
        return send(b);
    }

    private Optional<String> send(HttpRequest.Builder builder) {
        try {
            var resp = http.send(builder.timeout(timeout).build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() < 500 ? Optional.of(resp.body()) : Optional.empty();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    private static String base(int port) {
        return "http://127.0.0.1:" + port;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
