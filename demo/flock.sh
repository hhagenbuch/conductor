#!/usr/bin/env bash
# Flock: a breaking change in one repo, caught in another ... before it lands.
#
# Two sessions work in two DIFFERENT repos that never share a file. service-a
# owns the POST /orders request DTO; service-b consumes it. Session A adds a
# required field to the DTO. Conductor, through the real fathom graph and the
# real mcp-pact SchemaShape diff, tells the live session in service-b ... a
# cross-repo impact alert on the PENDING change, before any commit.
#
# Everything is real: the conductor daemon, the FlockEngine, a live `fathom
# serve` MCP subprocess, mcp-pact's classifier. No API key. The two "sessions"
# are registered over the same bus API a real Claude Code session uses.
#
# Reproduce from a clean clone (fathom must be built once):
#   (cd ../fathom && mvn -q -DskipTests package)
#   mvn -q -DskipTests package && FATHOM_JAR=../fathom/target/fathom.jar vhs demo/flock.tape
set -euo pipefail

JAR="${CONDUCTOR_JAR:-target/conductor.jar}"
FATHOM_JAR="${FATHOM_JAR:-../fathom/target/fathom.jar}"
FATHOM_JAR="$(cd "$(dirname "$FATHOM_JAR")" && pwd -P)/$(basename "$FATHOM_JAR")"

export CONDUCTOR_HOME; CONDUCTOR_HOME="$(cd "$(mktemp -d)" && pwd -P)"
WORK="$(cd "$(mktemp -d)" && pwd -P)"
A="$WORK/service-a"
B="$WORK/service-b"
trap 'kill $(cat "$CONDUCTOR_HOME/daemon.pid" 2>/dev/null) 2>/dev/null || true; rm -rf "$CONDUCTOR_HOME" "$WORK"' EXIT

echo "# Flock demo: building two repos + one fathom graph over both, starting the bus..."
# ---- two repos: service-a publishes a request DTO, service-b consumes it ----
mkdir -p "$A/src" "$B/src"
cat > "$A/src/OrderRequest.java" <<'EOF'
public record OrderRequest(String sku, int qty) {}
EOF
cat > "$B/src/Checkout.java" <<'EOF'
public class Checkout {
    OrderRequest req;
    void run() { var s = req.sku(); }
}
EOF
( cd "$A" && git init -q && git add -A && git -c user.email=t@t -c user.name=t commit -qm base )
( cd "$B" && git init -q && git add -A && git -c user.email=t@t -c user.name=t commit -qm base )

# ---- one fathom index over both repos, with the cross-repo edge ----
cat > "$WORK/fathom.yaml" <<EOF
workspace: flock-demo
db: $WORK/flock-demo.db
packs: [code]
sources:
  - connector: git
    path: $A
    options: { name: service-a }
  - connector: git
    path: $B
    options: { name: service-b }
EOF
java -jar "$FATHOM_JAR" index --config "$WORK/fathom.yaml" >/dev/null 2>&1

# ---- turn Flock on: point conductor at `fathom serve` (stdio MCP) ----
cat > "$CONDUCTOR_HOME/flock.properties" <<EOF
enabled=true
fathom_cmd=java -jar $FATHOM_JAR serve --config $WORK/fathom.yaml
throttle_minutes=10
additive=false
EOF

# ---- start the bus (it reads flock.properties on boot) ----
java -jar "$JAR" daemon >/dev/null 2>&1 &
echo $! > "$CONDUCTOR_HOME/daemon.pid"
until [ -f "$CONDUCTOR_HOME/daemon.port" ]; do sleep 0.1; done
PORT="$(cat "$CONDUCTOR_HOME/daemon.port")"
BUS="http://127.0.0.1:$PORT"
post() { curl -s -m 3 -H 'Content-Type: application/json' -d "$2" "$BUS$1" >/dev/null; }

# ---- both projects consent to observation (Flock needs it on BOTH sides) ----
java -jar "$JAR" observe "$A" >/dev/null
java -jar "$JAR" observe "$B" >/dev/null

# ---- register two sessions, one per repo ----
post /api/hook/SessionStart "{\"session_id\":\"a3f2c118\",\"cwd\":\"$A\",\"transcript_path\":\"$A/t.jsonl\"}"
post /api/hook/SessionStart "{\"session_id\":\"b7e19d04\",\"cwd\":\"$B\"}"

# Session A's transcript records that it is editing the DTO (redacted on ingest).
cat > "$A/t.jsonl" <<EOF
{"type":"assistant","timestamp":"2026-07-23T00:00:00Z","message":{"content":[{"type":"tool_use","name":"Edit","input":{"file_path":"$A/src/OrderRequest.java"}}]}}
EOF

inbox() { curl -s -m 3 "$BUS/api/inbox?session=b7e19d04&consume=false"; }
show_alert() { inbox | python3 -c 'import sys,json,textwrap
for m in json.load(sys.stdin)["messages"]:
    print("\n".join(textwrap.fill(l,76) for l in m["body"].splitlines()))'; }

echo "# Flock: two sessions, two repos that never share a file."
echo "$ conductor flock"
java -jar "$JAR" flock
echo
echo "$ conductor ps"
java -jar "$JAR" ps
echo
sleep 1

echo "── service-a/a3f2: adds a REQUIRED field to the POST /orders request DTO ──"
cat > "$A/src/OrderRequest.java" <<'EOF'
public record OrderRequest(String sku, int qty, String promoCode) {}
EOF
( cd "$A" && git --no-pager diff --unified=0 -- src/OrderRequest.java | grep -E '^[-+]public' | sed 's/^/   /' )
echo
echo "   ...A hasn't committed. On its next tool call, conductor evaluates the pending change:"
echo "   resolve_file → impacted_by (fathom graph) → SchemaShape diff (mcp-pact) → BREAKING"
echo

# A's PostToolUse fires the impact evaluation (fire-and-forget on the bus).
post /api/hook/PostToolUse "{\"session_id\":\"a3f2c118\"}"

# Wait for the advisory to land in service-b's inbox.
for _ in $(seq 1 40); do
  [ "$(inbox | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["messages"]))')" != "0" ] && break
  sleep 0.5
done

echo "── service-b/b7e19d04: an unprompted impact alert, before the break lands ──"
show_alert | sed 's/^/  /'
echo
echo "  Flock never blocked anything. It informed the one session that would break."
