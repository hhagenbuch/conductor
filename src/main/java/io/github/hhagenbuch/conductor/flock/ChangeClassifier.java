package io.github.hhagenbuch.conductor.flock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.mcppact.core.Finding;
import io.github.hhagenbuch.mcppact.core.Severity;
import io.github.hhagenbuch.mcppact.core.diff.SchemaShape;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// Grades a session's PENDING change to a contract-surface file against the
/// surface's shape, deciding whether it is worth an alert. The three grades
/// mirror mcp-pact's taxonomy and drive Flock's noise control:
///
///  - **BREAKING** ... a consumer that relied on the old shape can break
///    (removed/renamed field, type change, a newly-required field, an endpoint
///    path/verb change, a dropped column). Always alerts.
///  - **COMPAT** ... additive and backward compatible (new optional field, new
///    endpoint, new column). Off by default (`--flock-additive`).
///  - **INTERNAL** ... the surface's shape did not change (a method body moved,
///    a comment, a private helper). Never alerts.
///
/// It reads the *pending* change: the old shape from `git show HEAD:<path>`, the
/// new shape from the working tree, so it answers "if this lands, who breaks?"
/// before the commit. DTO/schema shape classification is delegated to
/// mcp-pact-core's `SchemaShape.diff` ... the same engine that guards contract
/// drift in CI, reused here for live change classification. Anything it cannot
/// parse grades INTERNAL (silence), never a false BREAKING.
public final class ChangeClassifier {

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Grade { BREAKING, COMPAT, INTERNAL }

    public record Result(Grade grade, String detail) {
        static Result internal(String why) {
            return new Result(Grade.INTERNAL, why);
        }
    }

    private final Path repoDir;

    public ChangeClassifier(Path repoDir) {
        this.repoDir = repoDir;
    }

    /// Classify the pending change to `repoRelPath`, a file fathom marked as a
    /// surface of `surfaceKind` (may be null/blank).
    public Result classify(String repoRelPath, String surfaceKind) {
        String oldSource = gitShowHead(repoRelPath);
        String newSource = readWorkingTree(repoRelPath);

        if (newSource == null) {
            return new Result(Grade.BREAKING, "surface file " + repoRelPath + " was deleted");
        }
        if (oldSource == null) {
            // A brand-new surface file: additive to consumers, not a break.
            return new Result(Grade.COMPAT, "new surface file " + repoRelPath);
        }
        if (oldSource.equals(newSource)) {
            return Result.internal("no pending change to " + repoRelPath);
        }

        var kind = surfaceKind == null ? "" : surfaceKind.toLowerCase();
        if (kind.contains("endpoint")) {
            return classifyEndpoint(oldSource, newSource);
        }
        if (kind.contains("column") || repoRelPath.endsWith(".sql")) {
            return classifyColumn(oldSource, newSource);
        }
        // api-dto, api-type, event-schema, api-file, or unknown → shape diff.
        return classifyDto(repoRelPath, oldSource, newSource);
    }

    private Result classifyDto(String label, String oldSource, String newSource) {
        var oldSchema = JavaShape.schemaOf(oldSource);
        var newSchema = JavaShape.schemaOf(newSource);
        if (oldSchema.path("properties").isEmpty() && newSchema.path("properties").isEmpty()) {
            return Result.internal("no DTO shape detected in " + label);
        }
        // consumer = what relied on the old shape, provider = the new shape.
        List<Finding> findings = SchemaShape.diff(label,
                SchemaShape.of(oldSchema), SchemaShape.of(newSchema));
        if (findings.isEmpty()) {
            return Result.internal(label + " changed but its shape did not");
        }
        boolean breaking = findings.stream()
                .anyMatch(f -> f.severity() == Severity.BREAKING || f.severity() == Severity.WARN);
        var detail = findings.stream()
                .map(f -> f.rule() + " (" + f.severity() + ")")
                .distinct().collect(Collectors.joining(", "));
        return breaking
                ? new Result(Grade.BREAKING, detail)
                : new Result(Grade.COMPAT, detail);
    }

    private static final Pattern MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern MAPPING_PATH = Pattern.compile(
            "(?:value|path)\\s*=\\s*\\{?\\s*\"([^\"]*)\"|^\\s*\"([^\"]*)\"", Pattern.MULTILINE);

    private Result classifyEndpoint(String oldSource, String newSource) {
        var oldMappings = mappings(oldSource);
        var newMappings = mappings(newSource);
        var removedOrChanged = new LinkedHashSet<>(oldMappings);
        removedOrChanged.removeAll(newMappings);
        if (!removedOrChanged.isEmpty()) {
            return new Result(Grade.BREAKING, "endpoint(s) removed or changed: "
                    + String.join(", ", removedOrChanged));
        }
        var added = new LinkedHashSet<>(newMappings);
        added.removeAll(oldMappings);
        if (!added.isEmpty()) {
            return new Result(Grade.COMPAT, "endpoint(s) added: " + String.join(", ", added));
        }
        return Result.internal("controller changed but its endpoint mappings did not");
    }

    private static Set<String> mappings(String source) {
        var out = new LinkedHashSet<String>();
        var m = MAPPING.matcher(source);
        while (m.find()) {
            var verb = m.group(1).toUpperCase();
            var args = m.group(2);
            var pm = MAPPING_PATH.matcher(args);
            var path = pm.find() ? (pm.group(1) != null ? pm.group(1) : pm.group(2)) : "";
            out.add(verb + " " + path);
        }
        return out;
    }

    private static final Pattern DROP_COLUMN = Pattern.compile(
            "(?i)drop\\s+column\\s+(\\w+)");
    private static final Pattern ALTER_TYPE = Pattern.compile(
            "(?i)alter\\s+column\\s+(\\w+)\\s+type");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "(?i)add\\s+column\\s+(\\w+)");

    private Result classifyColumn(String oldSource, String newSource) {
        var added = diffMatches(oldSource, newSource, ADD_COLUMN);
        var dropped = diffMatches(oldSource, newSource, DROP_COLUMN);
        var retyped = diffMatches(oldSource, newSource, ALTER_TYPE);
        if (!dropped.isEmpty() || !retyped.isEmpty()) {
            var d = new LinkedHashSet<String>();
            dropped.forEach(c -> d.add("drop " + c));
            retyped.forEach(c -> d.add("retype " + c));
            return new Result(Grade.BREAKING, "column change: " + String.join(", ", d));
        }
        if (!added.isEmpty()) {
            return new Result(Grade.COMPAT, "column(s) added: " + String.join(", ", added));
        }
        return Result.internal("schema file changed but no column add/drop/retype detected");
    }

    /// Matches present in the new source but not the old (a newly-introduced
    /// statement), so an existing DDL line doesn't re-fire on every edit.
    private static Set<String> diffMatches(String oldSource, String newSource, Pattern p) {
        var out = new LinkedHashSet<String>();
        var m = p.matcher(newSource);
        while (m.find()) {
            out.add(m.group(1));
        }
        var oldM = p.matcher(oldSource);
        var already = new LinkedHashSet<String>();
        while (oldM.find()) {
            already.add(oldM.group(1));
        }
        out.removeAll(already);
        return out;
    }

    // ---- git / io (fail-quiet) ----

    private String gitShowHead(String repoRelPath) {
        try {
            var pb = new ProcessBuilder("git", "show", "HEAD:" + repoRelPath);
            pb.directory(repoDir.toFile());
            pb.redirectErrorStream(false);
            var proc = pb.start();
            var stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            proc.getErrorStream().readAllBytes(); // drain so the process can exit
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            // exit 128 = path not in HEAD (new file); treat as "no old version"
            return proc.exitValue() == 0 ? stdout : null;
        } catch (Exception cannotRead) {
            return null;
        }
    }

    private String readWorkingTree(String repoRelPath) {
        try {
            var file = repoDir.resolve(repoRelPath);
            return Files.isRegularFile(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : null;
        } catch (Exception unreadable) {
            return null;
        }
    }
}
