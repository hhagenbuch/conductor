package io.github.hhagenbuch.conductor.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.conductor.daemon.Redactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/// Distills a Claude Code transcript (JSONL, one record per line ... schema
/// confirmed in GROUND-TRUTH.md §2.2) into a short, redacted digest: what the
/// session set out to do, which files it has touched, its most recent tool
/// calls, and how far along it is. Every text span that goes into the digest
/// is run through the blackbox redaction library on ingest ... nothing
/// unredacted is ever returned or stored.
public final class TranscriptDigest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FILES = 12;
    private static final int MAX_RECENT_TOOLS = 8;
    private static final int MAX_TASK_CHARS = 300;

    private static final java.util.Set<String> EDIT_TOOLS =
            java.util.Set.of("Write", "Edit", "MultiEdit", "NotebookEdit");

    public record Digest(String statedTask, List<String> filesTouched, List<String> recentToolCalls,
                         long lastActivityAt, int userTurns, int assistantTurns) {

        public static Digest empty() {
            return new Digest(null, List.of(), List.of(), 0, 0, 0);
        }
    }

    public static Digest fromFile(Path transcript) throws IOException {
        if (transcript == null || !Files.isReadable(transcript)) {
            return Digest.empty();
        }
        try (var lines = Files.lines(transcript)) {
            return fromLines(lines.toList());
        }
    }

    /// Pure over a list of JSONL lines ... the unit-test entry point.
    public static Digest fromLines(List<String> lines) {
        String statedTask = null;
        var files = new LinkedHashSet<String>();
        var recentTools = new ArrayList<String>();
        long lastActivity = 0;
        int userTurns = 0;
        int assistantTurns = 0;

        for (var line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            JsonNode rec;
            try {
                rec = JSON.readTree(line);
            } catch (Exception malformed) {
                continue; // a corrupt line is skipped, never fatal
            }
            var type = rec.path("type").asText("");
            lastActivity = Math.max(lastActivity, parseTimestamp(rec.path("timestamp")));
            var message = rec.path("message");

            switch (type) {
                case "user" -> {
                    userTurns++;
                    if (statedTask == null) {
                        var text = firstUserText(message);
                        if (text != null && !text.isBlank()) {
                            statedTask = Redactor.redact(truncate(text));
                        }
                    }
                }
                case "assistant" -> {
                    assistantTurns++;
                    for (var block : message.path("content")) {
                        if (block.path("type").asText("").equals("tool_use")) {
                            var name = block.path("name").asText("tool");
                            recentTools.add(name);
                            var fp = block.path("input").path("file_path").asText(null);
                            if (fp != null && EDIT_TOOLS.contains(name)) {
                                files.add(Redactor.redact(fp));
                            }
                        }
                    }
                }
                default -> { /* system/attachment/etc. contribute only a timestamp */ }
            }
        }

        var recent = recentTools.size() <= MAX_RECENT_TOOLS
                ? recentTools
                : recentTools.subList(recentTools.size() - MAX_RECENT_TOOLS, recentTools.size());
        return new Digest(statedTask, capped(files), List.copyOf(recent),
                lastActivity, userTurns, assistantTurns);
    }

    private static String firstUserText(JsonNode message) {
        var content = message.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            for (var block : content) {
                if (block.path("type").asText("").equals("text")) {
                    return block.path("text").asText(null);
                }
                if (block.isTextual()) {
                    return block.asText();
                }
            }
        }
        return null;
    }

    private static long parseTimestamp(JsonNode ts) {
        if (ts.isNumber()) {
            return ts.asLong();
        }
        if (ts.isTextual()) {
            try {
                return java.time.Instant.parse(ts.asText()).toEpochMilli();
            } catch (RuntimeException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static List<String> capped(LinkedHashSet<String> files) {
        if (files.size() <= MAX_FILES) {
            return List.copyOf(files);
        }
        return List.copyOf(files).subList(0, MAX_FILES);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_TASK_CHARS ? s : s.substring(0, MAX_TASK_CHARS) + "…";
    }

    private TranscriptDigest() {
    }
}
