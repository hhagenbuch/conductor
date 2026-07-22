package io.github.hhagenbuch.conductor.daemon;

import io.github.hhagenbuch.conductor.lease.Lease;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// The bus's shared state: sessions and messages, in one SQLite file owned
/// by the daemon. All methods are synchronized; at this scale a single
/// serialized writer is simpler and safer than connection pooling.
public final class Registry implements AutoCloseable {

    /// A session older than this (last_seen) is reported `stale`, never
    /// removed: a stale entry may still hold leases and is information.
    public static final long STALE_AFTER_MS = 120_000;

    /// Cap stored activity digests; Stop payloads can carry long messages.
    static final int MAX_ACTIVITY_CHARS = 500;

    public record Session(String sessionId, String projectDir, String gitBranch, String worktree,
                          String statedTask, String transcriptPath, long startedAt, long lastSeen,
                          boolean isChild, boolean observed, String lastActivity, Long endedAt,
                          String status) { }

    public record Message(long id, String fromSession, String toSession, String body, long createdAt) { }

    private final Connection conn;
    private final Clock clock;

    public Registry(Path dbFile, Clock clock) throws SQLException {
        this.clock = clock;
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        try (var st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=3000");
            st.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                  session_id      TEXT PRIMARY KEY,
                  project_dir     TEXT NOT NULL,
                  git_branch      TEXT,
                  worktree        TEXT,
                  stated_task     TEXT,
                  transcript_path TEXT,
                  started_at      INTEGER NOT NULL,
                  last_seen       INTEGER NOT NULL,
                  is_child        INTEGER NOT NULL DEFAULT 0,
                  observed        INTEGER NOT NULL DEFAULT 0,
                  last_activity   TEXT,
                  ended_at        INTEGER
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                  id           INTEGER PRIMARY KEY AUTOINCREMENT,
                  from_session TEXT NOT NULL,
                  to_session   TEXT NOT NULL,
                  body         TEXT NOT NULL,
                  created_at   INTEGER NOT NULL,
                  read_at      INTEGER
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS leases (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id  TEXT NOT NULL,
                  repo_id     TEXT NOT NULL,
                  kind        TEXT NOT NULL,
                  pattern     TEXT NOT NULL,
                  created_at  INTEGER NOT NULL,
                  expires_at  INTEGER NOT NULL,
                  note        TEXT
                )""");
        }
    }

    // ---- leases ----

    /// Default lease TTL when the caller does not specify one.
    public static final long DEFAULT_LEASE_TTL_MS = 60 * 60_000;

    public synchronized Lease claim(String sessionId, String repoId, Lease.Kind kind,
                                    String pattern, long ttlMs, String note) throws SQLException {
        long now = clock.millis();
        long expires = now + ttlMs;
        try (var ps = conn.prepareStatement(
                "INSERT INTO leases (session_id, repo_id, kind, pattern, created_at, expires_at, note)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sessionId);
            ps.setString(2, repoId);
            ps.setString(3, kind.name());
            ps.setString(4, pattern);
            ps.setLong(5, now);
            ps.setLong(6, expires);
            ps.setString(7, note);
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Lease(keys.getLong(1), sessionId, repoId, kind, pattern, now, expires, note);
            }
        }
    }

    /// Release one lease by id, only if the caller owns it. Returns true if a
    /// lease was released.
    public synchronized boolean release(String sessionId, long leaseId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "DELETE FROM leases WHERE id = ? AND session_id = ?")) {
            ps.setLong(1, leaseId);
            ps.setString(2, sessionId);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized int releaseAllForSession(String sessionId) throws SQLException {
        try (var ps = conn.prepareStatement("DELETE FROM leases WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            return ps.executeUpdate();
        }
    }

    /// Active (non-expired) leases in a repo. Expired leases are pruned lazily
    /// here so a crashed holder's leases free up on the next read.
    public synchronized List<Lease> activeLeases(String repoId) throws SQLException {
        pruneExpired();
        var out = new ArrayList<Lease>();
        try (var ps = conn.prepareStatement(
                "SELECT * FROM leases WHERE repo_id = ? ORDER BY created_at")) {
            ps.setString(1, repoId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(toLease(rs));
                }
            }
        }
        return out;
    }

    public synchronized List<Lease> allActiveLeases() throws SQLException {
        pruneExpired();
        var out = new ArrayList<Lease>();
        try (var ps = conn.prepareStatement("SELECT * FROM leases ORDER BY repo_id, created_at");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(toLease(rs));
            }
        }
        return out;
    }

    private void pruneExpired() throws SQLException {
        try (var ps = conn.prepareStatement("DELETE FROM leases WHERE expires_at <= ?")) {
            ps.setLong(1, clock.millis());
            ps.executeUpdate();
        }
    }

    private Lease toLease(ResultSet rs) throws SQLException {
        return new Lease(rs.getLong("id"), rs.getString("session_id"), rs.getString("repo_id"),
                Lease.Kind.valueOf(rs.getString("kind")), rs.getString("pattern"),
                rs.getLong("created_at"), rs.getLong("expires_at"), rs.getString("note"));
    }

    public synchronized void register(String sessionId, String projectDir, String gitBranch,
                                      String worktree, String transcriptPath, boolean isChild) throws SQLException {
        long now = clock.millis();
        try (var ps = conn.prepareStatement("""
                INSERT INTO sessions (session_id, project_dir, git_branch, worktree, transcript_path,
                                      started_at, last_seen, is_child)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                  project_dir = excluded.project_dir,
                  git_branch = excluded.git_branch,
                  worktree = excluded.worktree,
                  transcript_path = excluded.transcript_path,
                  last_seen = excluded.last_seen,
                  is_child = excluded.is_child,
                  ended_at = NULL""")) {
            ps.setString(1, sessionId);
            ps.setString(2, projectDir);
            ps.setString(3, gitBranch);
            ps.setString(4, worktree);
            ps.setString(5, transcriptPath);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setInt(8, isChild ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public synchronized void heartbeat(String sessionId) throws SQLException {
        try (var ps = conn.prepareStatement("UPDATE sessions SET last_seen = ? WHERE session_id = ?")) {
            ps.setLong(1, clock.millis());
            ps.setString(2, sessionId);
            ps.executeUpdate();
        }
    }

    public synchronized void recordActivity(String sessionId, String lastAssistantMessage) throws SQLException {
        var digest = Redactor.redact(truncate(lastAssistantMessage));
        try (var ps = conn.prepareStatement(
                "UPDATE sessions SET last_seen = ?, last_activity = ? WHERE session_id = ?")) {
            ps.setLong(1, clock.millis());
            ps.setString(2, digest);
            ps.setString(3, sessionId);
            ps.executeUpdate();
        }
    }

    public synchronized void end(String sessionId) throws SQLException {
        try (var ps = conn.prepareStatement("UPDATE sessions SET ended_at = ? WHERE session_id = ?")) {
            ps.setLong(1, clock.millis());
            ps.setString(2, sessionId);
            ps.executeUpdate();
        }
        // A cleanly-ended session holds no leases; TTL covers crashes and kills
        // where SessionEnd never fires.
        releaseAllForSession(sessionId);
    }

    /// Sessions for one project (canonical absolute path), or all when null.
    public synchronized List<Session> sessions(String projectDir) throws SQLException {
        var sql = "SELECT * FROM sessions" + (projectDir != null ? " WHERE project_dir = ?" : "")
                + " ORDER BY started_at";
        try (var ps = conn.prepareStatement(sql)) {
            if (projectDir != null) {
                ps.setString(1, projectDir);
            }
            var out = new ArrayList<Session>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(toSession(rs));
                }
            }
            return out;
        }
    }

    /// Other sessions sharing the given session's project (by the canonical
    /// repo identity recorded at registration). Excludes the session itself.
    /// The project is resolved server-side, so callers never recompute git
    /// identity locally.
    public synchronized List<Session> peersOf(String sessionId) throws SQLException {
        var self = session(sessionId);
        if (self.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<Session>();
        for (var s : sessions(self.get().projectDir())) {
            if (!s.sessionId().equals(sessionId)) {
                out.add(s);
            }
        }
        return out;
    }

    public synchronized Optional<Session> session(String sessionId) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT * FROM sessions WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toSession(rs)) : Optional.empty();
            }
        }
    }

    /// Delivers to a full session id or an unambiguous prefix.
    /// Returns the resolved recipient id, or empty if none/ambiguous.
    public synchronized Optional<String> post(String fromSession, String toPrefix, String body) throws SQLException {
        var candidates = new ArrayList<String>();
        try (var ps = conn.prepareStatement(
                "SELECT session_id FROM sessions WHERE session_id LIKE ? AND ended_at IS NULL")) {
            ps.setString(1, toPrefix + "%");
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(rs.getString(1));
                }
            }
        }
        if (candidates.size() != 1) {
            return Optional.empty();
        }
        try (var ps = conn.prepareStatement(
                "INSERT INTO messages (from_session, to_session, body, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, fromSession);
            ps.setString(2, candidates.getFirst());
            ps.setString(3, body);
            ps.setLong(4, clock.millis());
            ps.executeUpdate();
        }
        return Optional.of(candidates.getFirst());
    }

    /// Unread messages for a session; consuming marks them read.
    public synchronized List<Message> inbox(String sessionId, boolean consume) throws SQLException {
        var out = new ArrayList<Message>();
        try (var ps = conn.prepareStatement(
                "SELECT id, from_session, to_session, body, created_at FROM messages"
                + " WHERE to_session = ? AND read_at IS NULL ORDER BY id")) {
            ps.setString(1, sessionId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Message(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5)));
                }
            }
        }
        if (consume && !out.isEmpty()) {
            try (var ps = conn.prepareStatement(
                    "UPDATE messages SET read_at = ? WHERE to_session = ? AND read_at IS NULL")) {
                ps.setLong(1, clock.millis());
                ps.setString(2, sessionId);
                ps.executeUpdate();
            }
        }
        return out;
    }

    private Session toSession(ResultSet rs) throws SQLException {
        long lastSeen = rs.getLong("last_seen");
        long endedAtRaw = rs.getLong("ended_at");
        Long endedAt = rs.wasNull() ? null : endedAtRaw;
        String status = endedAt != null ? "ended"
                : clock.millis() - lastSeen > STALE_AFTER_MS ? "stale"
                : "active";
        return new Session(
                rs.getString("session_id"), rs.getString("project_dir"), rs.getString("git_branch"),
                rs.getString("worktree"), rs.getString("stated_task"), rs.getString("transcript_path"),
                rs.getLong("started_at"), lastSeen, rs.getInt("is_child") == 1,
                rs.getInt("observed") == 1, rs.getString("last_activity"), endedAt, status);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ACTIVITY_CHARS ? s : s.substring(0, MAX_ACTIVITY_CHARS) + "…";
    }

    @Override
    public synchronized void close() throws SQLException {
        conn.close();
    }
}
