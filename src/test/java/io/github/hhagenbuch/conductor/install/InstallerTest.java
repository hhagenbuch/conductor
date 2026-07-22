package io.github.hhagenbuch.conductor.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.conductor.ConductorHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InstallerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Installer installer(Path home) {
        return new Installer(ConductorHome.of(home.resolve(".conductor")));
    }

    @Test
    void initInstallsAllEventsAndIsAdditive(@TempDir Path tmp) throws Exception {
        var project = tmp.resolve("proj");
        var settings = project.resolve(".claude").resolve("settings.local.json");
        Files.createDirectories(settings.getParent());
        // A pre-existing user hook that conductor must NOT clobber.
        Files.writeString(settings, """
            {
              "hooks": {
                "PreToolUse": [
                  {"matcher": "Bash", "hooks": [{"type": "command", "command": "my-audit.sh"}]}
                ]
              },
              "model": "opus"
            }
            """);

        int rc = installer(tmp).init(project);
        assertEquals(0, rc);

        JsonNode root = JSON.readTree(Files.readString(settings));
        // Pre-existing content preserved.
        assertEquals("opus", root.path("model").asText());
        assertTrue(root.path("hooks").path("PreToolUse").isArray());
        assertEquals("my-audit.sh",
                root.path("hooks").path("PreToolUse").get(0).path("hooks").get(0).path("command").asText());
        // All five conductor events installed and marked.
        for (var event : Installer.hookEventNames()) {
            var groups = root.path("hooks").path(event);
            assertTrue(groups.isArray() && !groups.isEmpty(), "missing event " + event);
            boolean marked = false;
            for (var g : groups) {
                if (g.path(Installer.MARKER).asBoolean(false)) {
                    marked = true;
                    assertTrue(g.path("hooks").get(0).path("command").asText().contains(event));
                }
            }
            assertTrue(marked, "conductor group not marked for " + event);
        }
    }

    @Test
    void initIsIdempotent(@TempDir Path tmp) throws Exception {
        var project = tmp.resolve("proj");
        var settings = project.resolve(".claude").resolve("settings.local.json");
        var inst = installer(tmp);
        inst.init(project);
        inst.init(project);

        JsonNode root = JSON.readTree(Files.readString(settings));
        // Exactly one conductor group per event, not two.
        for (var event : Installer.hookEventNames()) {
            long conductorGroups = 0;
            for (var g : root.path("hooks").path(event)) {
                if (g.path(Installer.MARKER).asBoolean(false)) {
                    conductorGroups++;
                }
            }
            assertEquals(1, conductorGroups, "duplicate conductor group for " + event);
        }
    }

    @Test
    void removeRestoresPriorShapeExactly(@TempDir Path tmp) throws Exception {
        var project = tmp.resolve("proj");
        var settings = project.resolve(".claude").resolve("settings.local.json");
        Files.createDirectories(settings.getParent());
        var original = """
            {
              "hooks" : {
                "PreToolUse" : [ {
                  "matcher" : "Bash",
                  "hooks" : [ {
                    "type" : "command",
                    "command" : "my-audit.sh"
                  } ]
                } ]
              },
              "model" : "opus"
            }
            """.strip();
        Files.writeString(settings, original + "\n");

        var inst = installer(tmp);
        inst.init(project);
        inst.remove(project);

        // Byte-for-byte: conductor left no residue in a project that had a
        // pre-existing PreToolUse hook and no conductor events.
        var after = Files.readString(settings).strip();
        var reNormalized = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(JSON.readTree(after));
        var expected = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(JSON.readTree(original));
        assertEquals(expected, reNormalized, "remove must restore the prior settings shape");
    }

    @Test
    void removeOnEmptyProjectIsSafe(@TempDir Path tmp) throws Exception {
        var project = tmp.resolve("proj");
        assertEquals(0, installer(tmp).remove(project));
    }

    @Test
    void hookScriptFailsOpenAndIsExecutable(@TempDir Path tmp) throws Exception {
        var inst = installer(tmp);
        var script = inst.writeHookScript();
        assertTrue(Files.isExecutable(script));
        var body = Files.readString(script);
        assertTrue(body.contains("exit 0"), "hook must exit 0 (fail open)");
        assertTrue(body.contains("-m 1"), "curl must be time-bounded");
        assertFalse(body.contains("__CONDUCTOR_JAR__"), "jar placeholder must be substituted");
    }
}
