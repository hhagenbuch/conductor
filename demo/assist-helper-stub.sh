#!/usr/bin/env bash
# A SCRIPTED helper leaf for demo/assist.sh, standing in for the real
# `claude -p` helper so the demo reproduces from a clean clone with no API key.
# It exercises the real thing everywhere it matters: it works ONLY inside its
# own worktree, commits to its own branch (PR-ready, never the parent's tree),
# and reports progress to the parent over the real conductor bus.
#
# conductor injects the context via the environment (see ClaudeLauncher):
#   CONDUCTOR_WORKTREE, CONDUCTOR_TASK, CONDUCTOR_PARENT_ID, CONDUCTOR_HELPER_ID,
#   CONDUCTOR_HOME.
set -euo pipefail
cd "$CONDUCTOR_WORKTREE"

PORT="$(cat "${CONDUCTOR_HOME:-$HOME/.conductor}/daemon.port" 2>/dev/null || true)"
post() {
  [ -n "$PORT" ] || return 0
  curl -s -m 2 -H 'Content-Type: application/json' \
    -d "{\"from\":\"$CONDUCTOR_HELPER_ID\",\"to\":\"$CONDUCTOR_PARENT_ID\",\"body\":\"$1\"}" \
    "http://127.0.0.1:$PORT/api/messages" >/dev/null || true
}

post "on it — read the briefing, taking the test suite in my worktree"

# Do the assigned slice: the TEST suite only, staying strictly inside the
# leased scope (path:src/test/**), disjoint from the parent's src/main work.
mkdir -p src/test
cat > src/test/RetryTest.java <<'JAVA'
class RetryTest { /* exercises Retry.withBackoff: succeeds, retries, gives up */ }
JAVA
cat > src/test/BackoffTest.java <<'JAVA'
class BackoffTest { /* verifies the delay doubles each attempt */ }
JAVA

git add -A
git -c user.email=helper@conductor -c user.name=conductor-helper commit -qm \
  "Add the retry test suite (conductor assist helper)"

post "done: test suite committed on my branch; opening a PR (stayed in path:src/test/**, no conflict with your src/main work)"
