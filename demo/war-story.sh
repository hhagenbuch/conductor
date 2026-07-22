#!/usr/bin/env bash
# The conductor war story, reproducible from a clean clone with no API key.
#
# Two sessions are working in one repo. One claims the source tree AND the
# release branch. The other is blocked twice ... once for a conflicting file
# edit, once for a conflicting `git merge` ... by the real PreToolUse
# enforcement hook, each time with a message naming the holder and the way out.
#
# Both blocks run through the actual conductor daemon and the actual
# conductor-enforce.sh hook: the same wire a real Claude Code session uses.
# `vhs demo/war-story.tape` records it.
set -euo pipefail

JAR="${CONDUCTOR_JAR:-target/conductor.jar}"
export CONDUCTOR_HOME; CONDUCTOR_HOME="$(cd "$(mktemp -d)" && pwd -P)"
PROJ="$(cd "$(mktemp -d)" && pwd -P)"
trap 'kill $(cat "$CONDUCTOR_HOME/daemon.pid" 2>/dev/null) 2>/dev/null || true; rm -rf "$CONDUCTOR_HOME" "$PROJ"' EXIT

( cd "$PROJ" && git init -q && git checkout -q -b main && git commit -qm init --allow-empty )

# Install conductor's hooks (this also writes conductor-enforce.sh).
java -jar "$JAR" init "$PROJ" >/dev/null
ENFORCE="$CONDUCTOR_HOME/conductor-enforce.sh"

# Start the bus.
java -jar "$JAR" daemon >/dev/null 2>&1 &
echo $! > "$CONDUCTOR_HOME/daemon.pid"
until [ -f "$CONDUCTOR_HOME/daemon.port" ]; do sleep 0.1; done
PORT="$(cat "$CONDUCTOR_HOME/daemon.port")"
BUS="http://127.0.0.1:$PORT"
post() { curl -s -m 2 -H 'Content-Type: application/json' -d "$2" "$BUS$1" >/dev/null; }

reason() { python3 -c 'import sys,json,textwrap
r=json.load(sys.stdin)["hookSpecificOutput"]["permissionDecisionReason"]
print(textwrap.fill(r, 74))'; }

# Two sessions register in the same repo, on branch main.
post /api/hook/SessionStart "{\"session_id\":\"a3f2c118\",\"cwd\":\"$PROJ\"}"
post /api/hook/SessionStart "{\"session_id\":\"b7e19d04\",\"cwd\":\"$PROJ\"}"

# Session a3f2 claims the source tree and the release branch.
post /api/lease/claim '{"session":"a3f2c118","scope":"path:src/main/**","note":"refactoring the parser"}'
post /api/lease/claim '{"session":"a3f2c118","scope":"branch:main","note":"preparing the release merge"}'

echo "$ conductor ps"
java -jar "$JAR" ps
echo

echo "── Act 1 ── session b7e19d04 tries to EDIT a file a3f2 has leased:"
printf '%s' "{\"session_id\":\"b7e19d04\",\"cwd\":\"$PROJ\",\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$PROJ/src/main/Parser.java\",\"old_string\":\"a\",\"new_string\":\"b\"}}" \
  | "$ENFORCE" | reason | sed 's/^/  🚫 /'
echo

echo "── Act 2 ── the same session tries a conflicting \`git merge\` (the stale-main incident):"
printf '%s' "{\"session_id\":\"b7e19d04\",\"cwd\":\"$PROJ\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"git merge feature/experiment\"}}" \
  | "$ENFORCE" | reason | sed 's/^/  🚫 /'
