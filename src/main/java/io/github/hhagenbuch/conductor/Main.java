package io.github.hhagenbuch.conductor;

import io.github.hhagenbuch.conductor.cli.PsCommand;
import io.github.hhagenbuch.conductor.daemon.Daemon;
import io.github.hhagenbuch.conductor.install.Installer;
import io.github.hhagenbuch.conductor.shim.McpShim;

import java.nio.file.Path;
import java.util.Arrays;

/// Entry point for the shaded jar. One binary, several roles:
/// `daemon` (the shared bus backend), `mcp-shim` (per-session stdio MCP
/// server), and the human-facing CLI (`init`, `remove`, `ps`).
public final class Main {

    public static final String VERSION = "0.1.0";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        var rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "daemon" -> Daemon.run(ConductorHome.resolve());
            case "mcp-shim" -> McpShim.run(ConductorHome.resolve());
            case "init" -> System.exit(new Installer(ConductorHome.resolve()).init(projectArg(rest)));
            case "remove" -> System.exit(new Installer(ConductorHome.resolve()).remove(projectArg(rest)));
            case "ps" -> System.exit(PsCommand.run(ConductorHome.resolve()));
            case "version", "--version", "-v" -> System.out.println("conductor " + VERSION);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static Path projectArg(String[] rest) {
        return rest.length > 0 ? Path.of(rest[0]).toAbsolutePath().normalize()
                               : Path.of("").toAbsolutePath();
    }

    private static void usage() {
        System.err.println("""
            conductor - session orchestration for Claude Code

            usage: conductor <command>
              init [project-dir]    install conductor hooks into a project (per-user, never committed)
              remove [project-dir]  uninstall conductor hooks from a project
              ps                    show sessions known to the bus
              daemon                run the bus daemon in the foreground
              mcp-shim              run the per-session stdio MCP shim (invoked by Claude Code)
              version               print version
            """);
    }

    private Main() { }
}
