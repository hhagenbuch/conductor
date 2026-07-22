package io.github.hhagenbuch.conductor.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.conductor.ConductorHome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

/// Installs and removes conductor's session hooks for a project.
///
/// Safety, in priority order (a broken SessionStart hook degrades every
/// future session, so this is the most conservative code in the repo):
///
///  - Installs into `.claude/settings.local.json` (per-user, Claude Code
///    gitignores it) ... conductor is never committed onto a teammate.
///  - ADDITIVE: existing hooks are preserved; conductor appends its own
///    matcher groups, marked by a `_conductor: true` field so `remove` can
///    find exactly its own entries and nothing else.
///  - The hook script itself fails OPEN on every path: any error, missing
///    daemon, or timeout exits 0. It can never block a tool or break a
///    session. `remove` restores the settings file to its prior shape.
public final class Installer {

    static final String MARKER = "_conductor";
    private static final ObjectMapper JSON = new ObjectMapper();

    /// event name -> matcher (null = no matcher, fires for all).
    private static final List<String[]> HOOK_EVENTS = List.of(
            new String[]{"SessionStart", null},
            new String[]{"UserPromptSubmit", null},
            new String[]{"PostToolUse", null},
            new String[]{"Stop", null},
            new String[]{"SessionEnd", null});

    private final ConductorHome home;

    public Installer(ConductorHome home) {
        this.home = home;
    }

    public int init(Path project) {
        try {
            var script = writeHookScript();
            var enforceScript = writeEnforceScript();
            var settingsFile = project.resolve(".claude").resolve("settings.local.json");
            Files.createDirectories(settingsFile.getParent());
            ObjectNode settings = readOrEmpty(settingsFile);
            ObjectNode hooks = objectChild(settings, "hooks");

            for (var ev : HOOK_EVENTS) {
                installEvent(hooks, ev[0], ev[1], script, ev[0]);
            }
            // Lease enforcement: a PreToolUse hook on the mutating tools that
            // relays the daemon's allow/deny decision.
            installEvent(hooks, "PreToolUse", "Write|Edit|MultiEdit|NotebookEdit|Bash",
                    enforceScript, "");
            writePretty(settingsFile, settings);

            System.out.println("conductor: installed session hooks into " + settingsFile);
            System.out.println("  hook script: " + script);
            System.out.println("Hooks fail open: if the bus is down, sessions are never blocked.");
            System.out.println("Restart or start a Claude Code session in this project to register.");
            return 0;
        } catch (IOException e) {
            System.err.println("conductor init failed: " + e.getMessage());
            return 1;
        }
    }

    public int remove(Path project) {
        try {
            var settingsFile = project.resolve(".claude").resolve("settings.local.json");
            if (!Files.exists(settingsFile)) {
                System.out.println("conductor: nothing to remove (no settings.local.json).");
                return 0;
            }
            ObjectNode settings = (ObjectNode) JSON.readTree(Files.newInputStream(settingsFile));
            var hooksNode = settings.get("hooks");
            int removed = 0;
            if (hooksNode instanceof ObjectNode hooks) {
                var fields = hooks.fieldNames();
                var eventNames = new java.util.ArrayList<String>();
                fields.forEachRemaining(eventNames::add);
                for (var event : eventNames) {
                    if (hooks.get(event) instanceof ArrayNode groups) {
                        removed += stripConductor(groups);
                        if (groups.isEmpty()) {
                            hooks.remove(event);
                        }
                    }
                }
                if (hooks.isEmpty()) {
                    settings.remove("hooks");
                }
            }
            writePretty(settingsFile, settings);
            System.out.println("conductor: removed " + removed + " hook group(s) from " + settingsFile);
            return 0;
        } catch (IOException e) {
            System.err.println("conductor remove failed: " + e.getMessage());
            return 1;
        }
    }

    // ---- settings.json surgery ----

    private void installEvent(ObjectNode hooks, String event, String matcher, Path script, String arg) {
        ArrayNode groups = hooks.get(event) instanceof ArrayNode a ? a : hooks.putArray(event);
        // Idempotent: drop any prior conductor group for this event first.
        stripConductor(groups);
        ObjectNode group = groups.addObject();
        group.put(MARKER, true);
        if (matcher != null) {
            group.put("matcher", matcher);
        }
        ObjectNode hook = group.putArray("hooks").addObject();
        hook.put("type", "command");
        hook.put("command", arg == null || arg.isBlank()
                ? "\"" + script + "\""
                : "\"" + script + "\" " + arg);
        hook.put("timeout", 5);
    }

    private int stripConductor(ArrayNode groups) {
        int removed = 0;
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (groups.get(i).path(MARKER).asBoolean(false)) {
                groups.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private ObjectNode readOrEmpty(Path file) throws IOException {
        if (Files.exists(file) && Files.size(file) > 0) {
            var node = JSON.readTree(Files.newInputStream(file));
            if (node instanceof ObjectNode obj) {
                return obj;
            }
        }
        return JSON.createObjectNode();
    }

    private ObjectNode objectChild(ObjectNode parent, String field) {
        if (parent.get(field) instanceof ObjectNode existing) {
            return existing;
        }
        return parent.putObject(field);
    }

    private void writePretty(Path file, ObjectNode node) throws IOException {
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n");
    }

    /// The one hook script, dispatched by event-name argument. Written to
    /// conductor home (shared, one copy) and referenced by absolute path.
    Path writeHookScript() throws IOException {
        var jar = jarPath();
        var script = home.dir().resolve("conductor-hook.sh");
        var body = HOOK_SCRIPT.replace("__CONDUCTOR_JAR__", jar);
        Files.writeString(script, body);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX FS; command execution does not depend on the bit here
        }
        return script;
    }

    /// The PreToolUse enforcement script: a dumb relay. It POSTs the tool call
    /// to the daemon and, only if the daemon returns an explicit deny, prints
    /// that JSON verbatim (exit 0 with a deny in stdout is how a tool is
    /// blocked). Anything else ... allow, empty, timeout, down bus, parse
    /// error ... exits 0 and allows. Enforcement fails OPEN.
    Path writeEnforceScript() throws IOException {
        var script = home.dir().resolve("conductor-enforce.sh");
        Files.writeString(script, ENFORCE_SCRIPT);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX FS
        }
        return script;
    }

    private static String jarPath() {
        return new java.io.File(Installer.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath()).getAbsolutePath();
    }

    static final String ENFORCE_SCRIPT = """
        #!/usr/bin/env bash
        # conductor lease enforcement (PreToolUse). Fails OPEN: only an explicit
        # daemon "deny" blocks; any error, empty reply, timeout, or down bus
        # allows the tool. A dead coordinator must never stop work.
        set +e
        HOME_DIR="${CONDUCTOR_HOME:-$HOME/.conductor}"
        PORT_FILE="$HOME_DIR/daemon.port"
        PAYLOAD="$(cat)"
        PORT="$(cat "$PORT_FILE" 2>/dev/null)"
        [ -z "$PORT" ] && exit 0

        RESP="$(printf '%s' "$PAYLOAD" | curl -s -m 1 \\
          -H 'Content-Type: application/json' --data-binary @- \\
          "http://127.0.0.1:$PORT/api/enforce" 2>/dev/null)"
        [ -z "$RESP" ] && exit 0

        # The daemon returns the exact PreToolUse hook JSON on a block; relay it
        # verbatim so the reason reaches the model unmangled.
        case "$RESP" in
          *'"permissionDecision":"deny"'*)
            printf '%s' "$RESP"
            ;;
        esac
        exit 0
        """;

    /// Fails open on every path. `set +e`, `exit 0` at the end, curl bounded
    /// by `-m 1`. The daemon is started only on SessionStart, only if down.
    static final String HOOK_SCRIPT = """
        #!/usr/bin/env bash
        # conductor session hook. Installed by `conductor init`; removed by
        # `conductor remove`. Fails OPEN: any error exits 0, so a dead bus can
        # never block a tool call or break a session.
        set +e
        EVENT="$1"
        HOME_DIR="${CONDUCTOR_HOME:-$HOME/.conductor}"
        PORT_FILE="$HOME_DIR/daemon.port"
        JAR="__CONDUCTOR_JAR__"
        PAYLOAD="$(cat)"

        # Heartbeat throttle: at most one beat per session per 30s, so a hook
        # on every tool call does not become ambient overhead.
        if [ "$EVENT" = "UserPromptSubmit" ] || [ "$EVENT" = "PostToolUse" ]; then
          SID="$(printf '%s' "$PAYLOAD" | sed -n 's/.*"session_id"[: ]*"\\([^"]*\\)".*/\\1/p' | head -1)"
          if [ -n "$SID" ]; then
            MARK="$HOME_DIR/hb/$SID"
            if [ -f "$MARK" ]; then
              NOW=$(date +%s)
              LAST=$(stat -f %m "$MARK" 2>/dev/null || stat -c %Y "$MARK" 2>/dev/null || echo 0)
              [ $((NOW - LAST)) -lt 30 ] && exit 0
            fi
            mkdir -p "$HOME_DIR/hb" 2>/dev/null
            touch "$MARK" 2>/dev/null
          fi
        fi

        # Lazily start the daemon on session start if none is running.
        if [ ! -f "$PORT_FILE" ] && [ "$EVENT" = "SessionStart" ]; then
          if [ -n "$JAR" ] && [ -f "$JAR" ]; then
            nohup java -jar "$JAR" daemon >/dev/null 2>&1 &
          fi
          for _ in 1 2 3 4 5 6 7 8 9 10; do
            [ -f "$PORT_FILE" ] && break
            sleep 0.2
          done
        fi

        PORT="$(cat "$PORT_FILE" 2>/dev/null)"
        [ -z "$PORT" ] && exit 0

        HDR=()
        [ "$CLAUDE_CODE_CHILD_SESSION" = "1" ] && HDR=(-H "X-Conductor-Child: 1")

        printf '%s' "$PAYLOAD" | curl -s -m 1 "${HDR[@]}" \\
          -H "Content-Type: application/json" --data-binary @- \\
          "http://127.0.0.1:$PORT/api/hook/$EVENT" >/dev/null 2>&1

        exit 0
        """;

    /// Package-visible for tests that assert the marker set is complete.
    static Set<String> hookEventNames() {
        return HOOK_EVENTS.stream().map(e -> e[0]).collect(java.util.stream.Collectors.toSet());
    }
}
