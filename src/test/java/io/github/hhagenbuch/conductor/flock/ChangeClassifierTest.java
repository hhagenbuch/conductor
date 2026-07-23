package io.github.hhagenbuch.conductor.flock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The precision spec for grading a pending change. This is the table that
/// separates signal (BREAKING) from noise (COMPAT off by default, INTERNAL
/// silent). Each case commits an old version, rewrites the working tree, and
/// classifies the *pending* diff ... before any commit.
class ChangeClassifierTest {

    private static void git(Path dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        var pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.redirectErrorStream(true);
        var p = pb.start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
    }

    /// Commit `old` at HEAD, then write `pending` into the working tree, and
    /// return the classifier's grade for that pending change.
    private ChangeClassifier.Result gradeChange(Path repo, String relPath, String surfaceKind,
                                                String old, String pending) throws Exception {
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "t");
        var file = repo.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, old);
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "base");
        Files.writeString(file, pending); // pending working-tree change, uncommitted
        return new ChangeClassifier(repo).classify(relPath, surfaceKind);
    }

    @Test
    void addingARequiredFieldIsBreaking(@TempDir Path repo) throws Exception {
        var r = gradeChange(repo, "src/OrderRequest.java", "api-dto",
                "public record OrderRequest(String sku, int qty) {}",
                "public record OrderRequest(String sku, int qty, String promoCode) {}");
        assertEquals(ChangeClassifier.Grade.BREAKING, r.grade(), r.detail());
    }

    @Test
    void addingAnOptionalFieldIsCompat(@TempDir Path repo) throws Exception {
        var r = gradeChange(repo, "src/OrderRequest.java", "api-dto",
                "public record OrderRequest(String sku, int qty) {}",
                "public record OrderRequest(String sku, int qty, Optional<String> promoCode) {}");
        assertEquals(ChangeClassifier.Grade.COMPAT, r.grade(), r.detail());
    }

    @Test
    void removingAFieldIsBreaking(@TempDir Path repo) throws Exception {
        var r = gradeChange(repo, "src/OrderRequest.java", "api-dto",
                "public record OrderRequest(String sku, int qty) {}",
                "public record OrderRequest(String sku) {}");
        assertEquals(ChangeClassifier.Grade.BREAKING, r.grade(), r.detail());
    }

    @Test
    void changingAFieldTypeIsBreaking(@TempDir Path repo) throws Exception {
        var r = gradeChange(repo, "src/OrderRequest.java", "api-dto",
                "public record OrderRequest(String sku, int qty) {}",
                "public record OrderRequest(String sku, String qty) {}");
        assertEquals(ChangeClassifier.Grade.BREAKING, r.grade(), r.detail());
    }

    @Test
    void bodyOnlyChangeToASurfaceFileIsInternal(@TempDir Path repo) throws Exception {
        // The three-way AND's shape guard: the file is a surface, but its shape
        // did not change (only a comment / method body), so it must NOT alert.
        var r = gradeChange(repo, "src/OrderRequest.java", "api-dto",
                "public record OrderRequest(String sku, int qty) {\n  // v1\n}",
                "public record OrderRequest(String sku, int qty) {\n  // v2, tweaked\n}");
        assertEquals(ChangeClassifier.Grade.INTERNAL, r.grade(), r.detail());
    }

    @Test
    void changingAnEndpointPathIsBreaking(@TempDir Path repo) throws Exception {
        var r = gradeChange(repo, "src/OrderController.java", "endpoint",
                "class OrderController { @PostMapping(\"/orders\") void create() {} }",
                "class OrderController { @PostMapping(\"/orders/v2\") void create() {} }");
        assertEquals(ChangeClassifier.Grade.BREAKING, r.grade(), r.detail());
    }

    @Test
    void addingANewEndpointIsCompat(@TempDir Path repo) throws Exception {
        var r = gradeChange(repo, "src/OrderController.java", "endpoint",
                "class OrderController { @PostMapping(\"/orders\") void create() {} }",
                "class OrderController { @PostMapping(\"/orders\") void create() {}"
                        + " @GetMapping(\"/orders/{id}\") void get() {} }");
        assertEquals(ChangeClassifier.Grade.COMPAT, r.grade(), r.detail());
    }

    @Test
    void droppingAColumnIsBreaking(@TempDir Path repo) throws Exception {
        var r = gradeChange(repo, "db/V2__orders.sql", "column",
                "CREATE TABLE orders (id int, sku text);",
                "CREATE TABLE orders (id int, sku text);\nALTER TABLE orders DROP COLUMN sku;");
        assertEquals(ChangeClassifier.Grade.BREAKING, r.grade(), r.detail());
    }

    @Test
    void addingAColumnIsCompat(@TempDir Path repo) throws Exception {
        var r = gradeChange(repo, "db/V2__orders.sql", "column",
                "CREATE TABLE orders (id int);",
                "CREATE TABLE orders (id int);\nALTER TABLE orders ADD COLUMN sku text;");
        assertEquals(ChangeClassifier.Grade.COMPAT, r.grade(), r.detail());
    }

    @Test
    void aBrandNewSurfaceFileIsCompatNotBreaking(@TempDir Path repo) throws Exception {
        // committed base has a different file; the surface file is new in the tree
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "t");
        Files.writeString(repo.resolve("README"), "x");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "base");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/NewApi.java"), "public record NewApi(String a) {}");
        var r = new ChangeClassifier(repo).classify("src/NewApi.java", "api-dto");
        assertEquals(ChangeClassifier.Grade.COMPAT, r.grade(), r.detail());
    }
}
