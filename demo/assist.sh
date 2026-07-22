#!/usr/bin/env bash
# conductor assist, reproducible from a clean clone with no API key.
#
# A parent session is mid-task and holding the controller. One command spawns a
# briefed helper that takes a lease-DISJOINT slice in its own worktree, does the
# work, reports back over the bus, and leaves a PR-ready branch — never touching
# the parent's tree.
#
# The orchestration is 100% real (daemon, registry, leases, worktree, briefing,
# inbox). Only the helper's leaf process is scripted (demo/assist-helper-stub.sh)
# in place of a real `claude -p`, via conductor's CONDUCTOR_HELPER_CMD seam, so
# this runs offline and deterministically. The finish-faster TIMING claim is not
# made here — it is measured honestly in docs/CASE-STUDY.md from a real week.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd -P)"
JAR="${CONDUCTOR_JAR:-target/conductor.jar}"
JAR="$(cd "$(dirname "$JAR")" && pwd -P)/$(basename "$JAR")"

export CONDUCTOR_HOME; CONDUCTOR_HOME="$(cd "$(mktemp -d)" && pwd -P)"
export CONDUCTOR_HELPER_CMD="bash $HERE/assist-helper-stub.sh"
PARENT="$(cd "$(mktemp -d)" && pwd -P)/widget"
mkdir -p "$PARENT"
trap 'kill $(cat "$CONDUCTOR_HOME/daemon.pid" 2>/dev/null) 2>/dev/null || true' EXIT

( cd "$PARENT" && git init -q && git checkout -q -b main \
    && git -c user.email=t@t -c user.name=t commit -qm init --allow-empty )

java -jar "$JAR" daemon >/dev/null 2>&1 &
echo $! > "$CONDUCTOR_HOME/daemon.pid"
until [ -f "$CONDUCTOR_HOME/daemon.port" ]; do sleep 0.1; done
PORT="$(cat "$CONDUCTOR_HOME/daemon.port")"

# A parent session registers and claims the controller it is working on.
curl -s -m2 -H 'Content-Type: application/json' \
  -d "{\"session_id\":\"a3f2c118\",\"cwd\":\"$PARENT\"}" \
  "http://127.0.0.1:$PORT/api/hook/SessionStart" >/dev/null
curl -s -m2 -H 'Content-Type: application/json' \
  -d '{"session":"a3f2c118","scope":"path:src/main/**","note":"writing the retry logic"}' \
  "http://127.0.0.1:$PORT/api/lease/claim" >/dev/null

echo "$ conductor ps    # one session, holding src/main while it writes the retry logic"
( cd "$PARENT" && java -jar "$JAR" ps )
echo
echo "$ conductor assist a3f2c118 --task 'take the test suite' --claim path:src/test/** --allow Write,Bash"
( cd "$PARENT" && java -jar "$JAR" assist a3f2c118 \
    --task "write the retry test suite" \
    --claim "path:src/test/**" --allow "Write,Bash" )
echo
sleep 2   # let the scripted helper finish its slice
echo "$ conductor ps    # the helper is a child on its own branch, holding its own scope"
( cd "$PARENT" && java -jar "$JAR" ps )
echo
echo "Parent's inbox — the handoff and the helper's report:"
curl -s -m2 "http://127.0.0.1:$PORT/api/inbox?session=a3f2c118&consume=true" \
  | python3 -c 'import sys,json
for m in json.load(sys.stdin)["messages"]:
    print("  •", m["body"])'
echo
WT="$(ls -d "$(dirname "$PARENT")/widget-assist-"* 2>/dev/null | head -1)"
echo "Helper committed on its OWN branch (PR-ready); the parent tree is untouched:"
echo "  helper branch:  $(cd "$WT" && git log --oneline -1)"
echo "  parent HEAD:    $(cd "$PARENT" && git log --oneline -1)"
