#!/usr/bin/env bash
# The conductor war story, reproducible from a clean clone with no API key.
#
# Two sessions are working in one repo. One claims the source tree; the other
# tries to edit a file under it and is BLOCKED by the real PreToolUse
# enforcement hook, with a message that names the holder and the way out.
#
# This drives the actual conductor daemon and the actual conductor-enforce.sh
# hook ... the same wire a real Claude Code session uses. `vhs demo/war-story.tape`
# records it.
set -euo pipefail

JAR="${CONDUCTOR_JAR:-target/conductor.jar}"
export CONDUCTOR_HOME; CONDUCTOR_HOME="$(cd "$(mktemp -d)" && pwd -P)"
PROJ="$(cd "$(mktemp -d)" && pwd -P)"
trap 'kill $(cat "$CONDUCTOR_HOME/daemon.port.pid" 2>/dev/null) 2>/dev/null || true; rm -rf "$CONDUCTOR_HOME" "$PROJ"' EXIT

( cd "$PROJ" && git init -q && git checkout -q -b main && git commit -qm init --allow-empty )

# Install conductor's hooks (this also writes conductor-enforce.sh).
java -jar "$JAR" init "$PROJ" >/dev/null
ENFORCE="$CONDUCTOR_HOME/conductor-enforce.sh"

# Start the bus.
java -jar "$JAR" daemon >/dev/null 2>&1 &
echo $! > "$CONDUCTOR_HOME/daemon.port.pid"
until [ -f "$CONDUCTOR_HOME/daemon.port" ]; do sleep 0.1; done
PORT="$(cat "$CONDUCTOR_HOME/daemon.port")"
BUS="http://127.0.0.1:$PORT"

post() { curl -s -m 2 -H 'Content-Type: application/json' -d "$2" "$BUS$1" >/dev/null; }

# Two sessions register in the same repo.
post /api/hook/SessionStart "{\"session_id\":\"a3f2c118\",\"cwd\":\"$PROJ\"}"
post /api/hook/SessionStart "{\"session_id\":\"b7e19d04\",\"cwd\":\"$PROJ\"}"

# Session a3f2 claims the source tree while it refactors the parser.
post /api/lease/claim '{"session":"a3f2c118","scope":"path:src/main/**","note":"refactoring the parser"}'

echo "$ conductor ps"
java -jar "$JAR" ps
echo

echo "Now session b7e19d04 tries to edit a file a3f2 has leased:"
echo "$ (Claude Code fires the PreToolUse hook for a Write to src/main/Parser.java)"
echo

# Exactly the payload Claude Code sends the hook on a Write.
PAYLOAD="$(cat <<JSON
{"session_id":"b7e19d04","cwd":"$PROJ","tool_name":"Write",
 "tool_input":{"file_path":"$PROJ/src/main/Parser.java","content":"// ..."}}
JSON
)"

OUT="$(printf '%s' "$PAYLOAD" | "$ENFORCE")"

if printf '%s' "$OUT" | grep -q '"permissionDecision":"deny"'; then
  echo "🚫 Write BLOCKED. Claude Code shows the model:"
  echo
  printf '%s' "$OUT" | python3 -c 'import sys,json,textwrap
r=json.load(sys.stdin)["hookSpecificOutput"]["permissionDecisionReason"]
print(textwrap.fill(r, 72))'
else
  echo "allowed (unexpected in this demo)"
fi
