package io.github.hhagenbuch.conductor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// The machine-local state directory (`~/.conductor` by default,
/// `CONDUCTOR_HOME` overrides it for tests). Holds the SQLite database, the
/// daemon lock and port files, and the daemon log. Nothing in it is ever
/// part of any repository.
public record ConductorHome(Path dir) {

    public static ConductorHome resolve() {
        var env = System.getenv("CONDUCTOR_HOME");
        var dir = env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".conductor");
        return of(dir);
    }

    public static ConductorHome of(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create conductor home " + dir, e);
        }
        return new ConductorHome(dir.toAbsolutePath().normalize());
    }

    public Path db()          { return dir.resolve("conductor.db"); }
    public Path lockFile()    { return dir.resolve("daemon.lock"); }
    public Path portFile()    { return dir.resolve("daemon.port"); }
    public Path logFile()     { return dir.resolve("daemon.log"); }
    public Path flockConfig() { return dir.resolve("flock.properties"); }
}
