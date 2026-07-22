# conductor

Session orchestration for Claude Code: sessions aware of each other, talking to
each other, and coordinating on shared projects.

**Thesis:** sessions are workers; work needs a bus. Claude Code coordinates
subagents *within* a session and nothing *across* sessions ... no awareness, no
messaging, no conflict prevention. Conductor is that missing layer.

## Two war stories (why this exists)

Both are real incidents from this portfolio's history, and both are the same
shape: **two uncoordinated sessions, one project.**

1. **The stale-local-main squash merge.** A session branched from local `main`
   that held another session's unpushed commits, and dragged them into an
   unrelated squash merge under its own PR title.
2. **The launch-docs publication.** A session merged work that published draft
   documents another session was still writing.

Neither session knew the other existed. The first thing conductor gives you is
that they now do ... and the second is that it stops the collision before it
happens:

![Two sessions in one repo; the second is blocked from editing a file the first has leased, with a message naming the holder and the way out](demo/war-story.gif)

The recording is scripted, not hand-captured:
[`demo/war-story.tape`](demo/war-story.tape) drives
[`demo/war-story.sh`](demo/war-story.sh) with
[vhs](https://github.com/charmbracelet/vhs) against the real conductor daemon
and the real PreToolUse enforcement hook, so `vhs demo/war-story.tape`
reproduces it from a clean clone. No network, no API key.

## Status

**Phases 1-2 are shipped.** Phase 1: a machine-local daemon owns a SQLite
registry behind a localhost HTTP API; a per-session stdio MCP shim gives each
session the bus tools; session hooks (installed by `conductor init`)
auto-register and heartbeat. Phase 2: advisory **leases** with a PreToolUse
enforcement hook that blocks a conflicting Write, Edit, or history-moving git
command with a message naming the holder ... and fails open if the bus is down.
Transcript briefing (Phase 3) and `assist` (Phase 4) are next ... see
[docs/DESIGN.md](docs/DESIGN.md).

The design and the ground-truth verification it rests on came first:
[docs/GROUND-TRUTH.md](docs/GROUND-TRUTH.md) (every Claude Code mechanic
LIVE-verified vs docs-sourced) and [docs/DESIGN.md](docs/DESIGN.md).

## Install

```console
$ mvn -DskipTests package
$ java -jar target/conductor.jar init          # installs hooks into ./.claude/settings.local.json
```

`init` writes into `.claude/settings.local.json` (per-user, Claude Code
gitignores it) so conductor is never committed onto a teammate. It is
**additive** ... your existing hooks are preserved ... and `conductor remove`
strips exactly conductor's entries and nothing else.

Register the MCP shim once (user scope covers every project):

```console
$ claude mcp add-json --scope user conductor \
    '{"type":"stdio","command":"java","args":["-jar","'"$PWD"'/target/conductor.jar","mcp-shim"]}'
```

## Use

Inside any Claude Code session in a project where you ran `conductor init`:

- **`who_else`** ... who else is on this project, on what branch, how fresh, and
  what they last did.
- **`post` / `inbox`** ... session-to-session mail, addressed by session id or an
  unambiguous prefix.
- **`claim` / `release` / `leases`** ... take an advisory lease on a scope
  (`repo:`, `path:<glob>`, `branch:<name>`) before you edit it. A second session
  that tries a conflicting Write, Edit, or history-moving git command is blocked
  by the PreToolUse hook with a message naming you and the way out. Leases carry
  a TTL, release explicitly or on session end, and are **unenforced (fail open)
  if the daemon is down** ... a dead coordinator never stops work.

From the shell:

```console
$ java -jar target/conductor.jar ps          # live sessions known to the bus
SESSION    STATUS   PROJECT                BRANCH           SEEN
a3f2c118   active   …/Workspaces/personal  feature/parser   4s ago
    last: Added the tokenizer and its tests; starting on the parser.
7f2b9d0e   stale    …/Workspaces/personal  feature/tests    3m ago
```

A `stale` session is shown, never hidden: it may still hold work you should
know about.

### The inbox convention

Conductor ships **polling-first** (design decision D2): no magic context
injection, so you can always see in the transcript exactly when a session
learned something. Add this to your project's `CLAUDE.md` so sessions check in
at the natural boundaries:

```markdown
## Coordinating with other sessions (conductor)
- At task start, and before any commit, PR, or merge: call `who_else`, then
  `inbox`. If another session is on this project, coordinate over `post`
  before touching shared files.
```

Hook-injected delivery (push, via UserPromptSubmit) is a documented opt-in for
later; polling is the honest default.

## How it holds together when things break

- **Bus death fails open.** If the daemon is down, hooks exit 0 (never block a
  tool), `ps` says loudly that leases are unenforced, and bus tools return a
  "bus unavailable" message instead of vanishing. A dead coordinator must not
  stop work.
- **One daemon per machine**, enforced by an exclusive file lock taken before
  binding; the port file is written last, so finding it means the bus is up.
- **Heartbeats are throttled** inside the hook (one beat per session per ~30s)
  so a hook on every tool call never becomes ambient overhead.

## Authority boundary

Conductor never edits code, never merges, never approves. It schedules,
informs, and (from Phase 2) blocks conflicting writes ... the same authority
boundary as agent-medic's Surgeon: the component with system-wide visibility
gets observation and veto, never a pen.

## Contract

The bus's MCP surface is contract-tested with
[mcp-pact](https://github.com/hhagenbuch/mcp-pact) in CI
([pacts/conductor-bus.mcp-pact.json](pacts/conductor-bus.mcp-pact.json)):
renaming or reshaping `who_else` / `post` / `inbox` becomes a red build.
