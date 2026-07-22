# DESIGN: conductor

Session orchestration for Claude Code. Sessions are workers; work needs a bus.

Everything here builds only on mechanics proven in
[GROUND-TRUTH.md](GROUND-TRUTH.md) (verified 2026-07-22 against Claude Code
v2.1.217). Where a decision depends on an experiment, the experiment is cited.

## Why this exists

Claude Code coordinates subagents *within* a session and nothing *across*
sessions. Two real incidents motivated this tool, both of the shape "two
uncoordinated sessions, one project":

1. **The stale-local-main squash merge.** A session branched from local
   `main` that held another session's unpushed commits and dragged them into
   an unrelated squash merge under its own PR title.
2. **The launch-docs publication.** A session merged work that published
   draft documents another session was still writing.

Neither session knew the other existed. Being precise about what prevents
what: **file-edit conflicts** are prevented by leases (the PreToolUse deny
path on Write|Edit). But both incidents above were **git-level operations
executed through Bash** ... a branch cut from stale local main and a squash
merge ... and those sail past a Write|Edit matcher. They are covered two ways:

- **Structurally**, for spawned helpers: `assist` puts every helper in its
  own worktree and integrates via PR. A helper cannot repeat the incidents
  because it never touches the parent's tree or merges anything itself.
- **Best-effort**, for any session: lease enforcement also matches Bash and
  classifies git commands (push, merge, rebase, checkout/switch with `-b`/
  `-c`, reset) against `branch:` and `repo:` scopes. This is imperfect by
  nature (see § Leases) and is claimed as a tripwire, not a guarantee.

Two independently-started sessions on one checkout are therefore protected
firmly for file edits, best-effort for git operations, and by convention
(worktree discipline, registry awareness) beyond that. The registry is what
makes the convention followable: you cannot coordinate with a session you
cannot see.

## Architecture

```
 session A ──stdio shim──┐                    ┌── tailer (consent-gated) ── transcripts/*.jsonl
 session B ──stdio shim──┼── conductor daemon ┤
 helper s7 ──stdio shim──┘   (localhost HTTP, └── spawner ── claude -p --session-id s7
        ▲                     SQLite)                        (git worktree, briefing bundle)
        └── hooks: SessionStart=register · UserPromptSubmit/PostToolUse=heartbeat
                   PreToolUse(Write|Edit)=lease check · Stop=digest · SessionEnd=deregister
```

One daemon per machine owns the SQLite database. Sessions touch it two ways:
hooks (installed by `conductor init`) call the daemon's HTTP API with hard
timeouts, and the MCP bus tools go through a thin per-session stdio shim
speaking the same API.

## D1: shared backend, and shim vs direct HTTP MCP

**The experiment settles the first half:** three sessions mounting the same
stdio MCP config spawned three separate server processes (GROUND-TRUTH § 4.2).
A stdio MCP server is per-session state; the registry, inbox, and leases must
live in a shared backend. That backend is a single daemon owning SQLite over
localhost HTTP.

**The second half is transport:** Claude Code speaks HTTP MCP natively, so the
daemon could be registered directly as an HTTP MCP server, no shim at all.
We choose the **stdio shim** anyway, for three reasons:

1. **Lazy start.** The shim always launches (Claude Code spawns it per
   session), so it can start the daemon on demand. A direct HTTP registration
   fails at session start if the daemon is down, and that session simply has
   no bus tools for its lifetime.
2. **Stable tool surface, graceful degradation.** The shim always completes
   the MCP handshake; when the daemon is unreachable a tool call returns a
   clear "bus unavailable, leases unenforced" error instead of the whole
   server being absent. The model can react to a tool error; it cannot react
   to tools that never mounted.
3. **One registration, no port coupling.** The shim owns discovery of the
   daemon (port file under `~/.conductor/`), so the user-scope MCP
   registration never encodes a port.

Lazy start has a named race: several sessions starting at once must not each
launch a daemon. The daemon takes an exclusive file lock under
`~/.conductor/` before binding and writes the port file last; a shim that
loses the start race waits briefly and reads the winner's port file. Exactly
one daemon per machine, enforced by the lock, not by hope.

Cost: one small shim process per session (the platform already spawns
per-session processes for every stdio server; this adds nothing new). Direct
HTTP registration stays documented as an alternative for setups where the
daemon is supervised externally.

## D2: inbox delivery ... polling first

Two ways a message reaches a session: the session calls `inbox` (polling), or
a UserPromptSubmit hook injects pending messages as `additionalContext`
(mechanism LIVE-proven, GROUND-TRUTH § 1.5).

**v1 ships polling.** A CLAUDE.md convention (installed by `conductor init`)
instructs agents to check `inbox` at natural boundaries: task start, before
commits/PRs, when blocked. Rationale: no magic ... the user can read the
transcript and see exactly when and why the agent learned something; no
token tax on every prompt; no surprise context in sessions that never asked.

Hook injection ships as a **documented opt-in** (`conductor init
--inject-inbox`) because the mechanism is verified and some users will prefer
push. The injected payload is bounded (the 10k `additionalContext` cap is
platform-enforced) and marked as coming from conductor.

## Components

### Registry

SQLite table of sessions: `session_id`, `project_dir`, `git_branch`,
`worktree`, `stated_task`, `started_at`, `last_seen`, `is_child`
(`CLAUDE_CODE_CHILD_SESSION`), `observed` (transcript consent, § Privacy),
`last_activity` (the Stop hook's `last_assistant_message`, truncated and
redacted ... a consent-free digest the platform hands us for nothing).

Lifecycle: SessionStart hook registers; UserPromptSubmit and PostToolUse
heartbeat (fire-and-forget, sub-second timeout); Stop updates
`last_activity`; SessionEnd deregisters **and releases the session's
leases** (TTL expiry covers crashes and kills, where SessionEnd never
fires). A session past its heartbeat TTL shows as `stale`, never silently
vanishes ... `conductor ps` prints it with its age, because a stale entry is
information ("something was here and may still hold leases").

Heartbeat cost is bounded inside the hook: PostToolUse fires on every tool
call in every session, so the hook script keeps a per-session marker file
and skips the HTTP call when the last beat is younger than ~30 seconds. The
steady-state overhead is one curl per session per half minute, not one per
tool call.

### Bus tools (MCP, via shim)

`who_else`, `post`, `inbox`, `claim`, `release`, `leases`, `brief_me`,
`assist`. The surface gets an mcp-pact contract file from day one; CI
verifies it.

### Leases

Advisory locks with TTL on scopes: `repo:<name>`, `path:<glob>`,
`branch:<name>`. Claimed via `claim`, dropped via `release` or TTL expiry.
No waiting, no queueing in v1: a conflict fails loud and the parties
negotiate over the bus. Deadlock policy is therefore trivial: there is
nothing to deadlock on.

Enforcement is a PreToolUse hook with two matchers:

- **`Write|Edit`** (firm): resolve the target path against active `path:` and
  `repo:` leases; on conflict, emit `permissionDecision: "deny"` with a
  reason that names the holder, its task, the lease expiry, and the way out
  ("`post` session a3f2 or `claim` disjoint work"). The entire deny path is
  LIVE-proven (GROUND-TRUTH § 1.4): the model receives the reason verbatim
  and the denial is recorded in `permission_denials`.
- **`Bash`** (best-effort): a small classifier over the command string flags
  git operations that move branches or history ... `git push`, `git merge`,
  `git rebase`, `git checkout -b` / `git switch -c`, `git reset` ... and
  checks them against `branch:` and `repo:` scopes. This covers the actual
  incident class (both war stories were git commands), and it is **honestly
  imperfect**: `sed -i`, scripts that shell out to git, aliases, and
  compound commands can evade it. DESIGN claims it as a tripwire that
  catches the common spellings, not a security boundary. The classifier is
  pure string analysis with a fail-open default: anything it cannot parse
  passes.

Path matching canonicalizes before comparison (absolute paths, symlinks
resolved), and **repo identity comes from the git common dir, not the cwd**:
two worktrees of one repository have different paths but must resolve to the
same `repo:` scope, and `git rev-parse --git-common-dir` is what actually
identifies the repository.

### Transcript awareness (consent-gated)

Opt-in per project, **per user, per machine**: `conductor observe <project>`
writes a consent file (`.conductor-consent`) and adds it to the project's
`.git/info/exclude` itself, so it can never be committed. Consent is not a
project property: the transcripts being tailed are one user's local files,
and a committed consent file would opt in every future contributor's local
transcripts without their knowledge. There is no committed variant. Without
the local file, the tailer never opens that project's transcripts. The daemon
tails registered sessions' JSONL (path taken from hook payloads, never
guessed), keeps a rolling digest per session (current task, files touched,
last tool calls, timestamps), and runs blackbox's redaction patterns **on
ingest** ... nothing unredacted is ever stored or shown. `conductor ps` marks
observed sessions visibly.

### Briefing composer

`brief_me <session-id>` distills registry entry + digest into a briefing:
stated task, decisions made, files touched, current blocker, leases held,
`generated_at`. It is a file handed to a helper's first prompt ... context
transfer without context sharing. Every briefing is stamped with its
generation time and the parent's `last_seen`; consumers are told (in the
bundle itself) to re-`brief_me` before decisions that depend on parent state.

### Assist spawner

`conductor assist <session-id> [--task "..."]`:

1. The human (or calling agent) names the work split; v1 does no
   auto-decomposition.
2. Create a git worktree + `feature/` branch for the helper.
3. Pre-assign the helper's session id (`--session-id <uuid>`), register it
   and its leases **before** launch, so the parent can see it immediately.
4. Launch headless `claude -p` in the worktree with the briefing bundle and
   explicit `--allowedTools` scoping (GROUND-TRUTH § 3: permissive
   bypass flags may be policy-blocked; scoped allowlists are both the
   working and the correct mechanism).
5. Wire both inboxes: parent gets "helper s7 has taken X", helper's briefing
   says how to `post` back.

Helpers integrate via PR, like any colleague. They never edit the parent's
tree. This is what retires the stale-local-main incident class.

## The five tensions

### 1. Merge semantics

Worktrees + PR integration, always. Same-tree assist is explicitly out of
scope in v1 ... both motivating incidents were same-tree corruption, and a
shared checkout has no locking. The lease system protects even the
PR route (two worktrees still share a remote and a default branch), and the
PR gate adds the review point where a human catches what leases cannot.

### 2. Staleness

A briefing is a snapshot and says so: `generated_at` on the bundle,
timestamps on every digest line, and an instruction in the bundle itself to
re-`brief_me` before decisions that depend on the parent's current state
(e.g. "is the parent done with the migration?"). Registry freshness is
first-class: `who_else` returns `last_seen` ages, and stale sessions are
labeled, not hidden. We accept eventual consistency everywhere except
leases, which are checked at enforcement time against SQLite directly.

### 3. Privacy

Transcripts contain everything the user typed. Rules, in force from Phase 3:

- **Consent per project, per user, per machine**, via an explicit local-only
  file conductor writes only when asked and gitignores itself
  (`.git/info/exclude`). Never committable ... consent to tail local
  transcripts cannot be granted on another user's behalf. No consent, no
  tailing ... sessions in unconsented projects are registry-only.
- **Redaction on ingest** (blackbox patterns), before storage or display.
- **Observed-by markers**: `conductor ps` and `who_else` show which sessions
  are being tailed. No silent observation.
- **No transcript content in any repo.** The repo's own test fixtures are
  synthetic transcripts, and CI enforces it: a guard test fails if any
  fixture contains markers of real transcript provenance (real usernames,
  real project slugs, this machine's paths) or a seeded-secret pattern
  survives redaction.

### 4. Bus death

The daemon dying must not stop work:

- **Hooks fail open.** The platform already guarantees non-0/2 exit codes
  never block (GROUND-TRUTH § 1.4); conductor's hooks additionally trap
  their own failures and exit 0 on any daemon-unreachable condition, with a
  hard sub-second timeout. Proven by test in Phase 2: kill the daemon,
  edits proceed.
- **Loud, not silent.** The CLI banner on `conductor ps` (and a tool error
  on any bus call) says leases are unenforced while the daemon is down.
- **Reads degrade.** Registry reads served from the last SQLite state,
  marked stale. The shim reports "bus unavailable" per call instead of
  unmounting.

### 5. The orchestrator's authority

Conductor never edits code, never merges, never approves. It schedules,
informs, and blocks conflicting writes ... the same authority boundary as
agent-medic's Surgeon doctrine: the component with system-wide visibility
gets observation and veto, never a pen. Concretely: no bus tool mutates a
working tree; `assist` creates a worktree and a process, not code; lease
enforcement can only *deny* an action, never perform one. If conductor is
compromised or wrong, the blast radius is a blocked edit and a misleading
message, not a bad merge.

## Stack

- **Daemon:** Java 25 + SQLite (castaway precedent), localhost HTTP,
  single-writer.
- **Shim:** thin stdio MCP process per session (official MCP SDK), forwarding
  to the daemon.
- **Hooks:** small shell scripts installed by `conductor init` into project
  or user settings; every one carries a timeout and a fail-open trap;
  `conductor remove` uninstalls cleanly (tested).
- **CLI:** house shaded-jar pattern (`conductor init|ps|observe|assist|remove`).
- **Contracts:** mcp-pact file for the bus surface, verified in CI.
- **Redaction:** agent-blackbox redaction library as a dependency.

## Phases

1. **Bus + registry + hooks.** Daemon, register/heartbeat/who_else/post/inbox,
   `conductor init` and `ps`. Exit test: two real sessions see each other.
2. **Leases + enforcement.** claim/release/TTL, PreToolUse enforcement,
   fail-open-on-daemon-death test. Demo #1: the war-story GIF.
3. **Transcript awareness + briefing.** Consent flow, tailer, redacted
   digests, brief_me. Synthetic fixtures + CI privacy guard.
4. **Assist.** Opens by promoting `--session-id` from DOCS to LIVE with a
   five-minute experiment (pre-registration depends on it; verify before
   building on it, not mid-phase). Then worktree, pre-registered spawn,
   inbox wiring, PR integration. Demo #2: the finish-faster GIF.
5. **Dogfood + case study.** Two real RFC weeks run as conductor-coordinated
   sessions; CASE-STUDY.md from real bus logs; playbook chapter.

## Non-goals (v1)

Auto-decomposition of work; waiting/queueing on leases; same-tree assist;
cross-machine buses; observing sessions without consent; any write access to
working trees from conductor itself.
