package io.github.hhagenbuch.conductor.flock;

import java.util.List;

/// The slice of fathom the Flock engine depends on: reverse file→entity
/// resolution, surface-aware cross-repo impact, and a reachability probe. An
/// interface so the engine can be exercised against an in-memory fake without a
/// real `fathom serve` subprocess; {@link FathomClient} is the production
/// stdio-JSON-RPC implementation.
public interface FathomGraph extends AutoCloseable {

    /// A file resolved to zero or more graph entity ids (`Type:value`).
    record Entities(List<String> ids) {
        static final Entities EMPTY = new Entities(List.of());
    }

    /// One cross-repo consumer of a changed entity.
    record Consumer(String repo, String entity, String edgeKind, boolean surface) { }

    /// The `impacted_by` result: whether the changed entity is itself a declared
    /// contract surface (and of what kind), and its cross-repo consumers.
    record Impact(String root, boolean surface, String surfaceKind, List<Consumer> consumers) {
        static final Impact EMPTY = new Impact(null, false, null, List.of());
    }

    Entities resolveFile(String repo, String path);

    Impact impactedBy(String entityId);

    boolean reachable();

    @Override
    void close();
}
