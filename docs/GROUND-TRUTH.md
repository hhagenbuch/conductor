# Ground truth: the Claude Code integration surface conductor builds on

Every finding below is stamped with how it was established:

- **[LIVE]** ... verified by experiment on this machine (macOS, Claude Code
  v2.1.217, 2026-07-22) in a scratch project with payload-dumping hooks,
  headless runs, and a PID-logging stdio MCP server.
- **[DOCS]** ... taken from the official docs at code.claude.com on 2026-07-22
  (URLs inline). Docs facts that were also confirmed live are marked [LIVE].

Claude Code changes fast. Anything here can rot; re-verify against the
installed version before relying on it in a new phase. If a [DOCS] fact and a
[LIVE] observation ever disagree, the live observation wins.

---

## 1. Hooks

Sources: https://code.claude.com/docs/en/hooks.md and
https://code.claude.com/docs/en/hooks-guide.md (fetched 2026-07-22).

### 1.1 Events

**[DOCS]** Current event list (30 events as of v2.1.217): SessionStart, Setup,
UserPromptSubmit, UserPromptExpansion, PreToolUse, PermissionRequest,
PermissionDenied, PostToolUse, PostToolUseFailure, PostToolBatch, Notification,
MessageDisplay, SubagentStart, SubagentStop, TaskCreated, TaskCompleted, Stop,
StopFailure, TeammateIdle, InstructionsLoaded, ConfigChange, CwdChanged,
FileChanged, WorktreeCreate, WorktreeRemove, PreCompact, PostCompact,
Elicitation, ElicitationResult, SessionEnd.

**[LIVE]** verified on 2026-07-22, Claude Code v2.1.217: SessionStart,
UserPromptSubmit, PreToolUse (matcher `Write|Edit`), PostToolUse, Stop, and
SessionEnd all fired in a single headless `claude -p` run and delivered JSON
payloads on stdin.

Conductor uses: SessionStart (register), UserPromptSubmit + PostToolUse
(heartbeat), PreToolUse on Write|Edit (lease check), Stop (activity digest),
SessionEnd (deregister).

### 1.2 Payload contract

**[LIVE]** verified on 2026-07-22, Claude Code v2.1.217. Every event payload
contains at least:

```json
{
  "session_id": "5958b8fc-bbc4-477b-9b33-d1edb9a10f94",
  "transcript_path": "/Users/<user>/.claude/projects/<cwd-slug>/<session-id>.jsonl",
  "cwd": "/abs/path/of/project",
  "hook_event_name": "SessionStart"
}
```

Event-specific additions observed live:

- SessionStart: `source` (`"startup"`).
- PreToolUse: `tool_name`, `tool_input` (full arguments, e.g. `file_path` and
  `content` for Write), `tool_use_id`, `permission_mode`, `prompt_id`.
- Stop: `last_assistant_message` (full text of the final assistant message),
  `stop_hook_active`, `background_tasks`, `session_crons`, `prompt_id`,
  `permission_mode`.

**Design consequence:** `transcript_path` is handed to every hook, so the
tailer never computes the project slug itself. And `last_assistant_message` on
Stop gives a free "what did X just finish" digest with **no transcript access
at all** ... a consent-free awareness tier.

### 1.3 Environment visible to hooks

**[LIVE]** verified on 2026-07-22, Claude Code v2.1.217. Observed in the hook
process environment:

| Variable | Observed value / meaning |
|---|---|
| `CLAUDE_CODE_SESSION_ID` | the session UUID (matches payload `session_id`) |
| `CLAUDE_PROJECT_DIR` | project root (documented; use for hook paths) |
| `CLAUDE_PID` | pid of the claude process |
| `CLAUDE_ENV_FILE` | per-session env preamble script path |
| `CLAUDE_CODE_ENTRYPOINT` | `sdk-cli` for headless runs |
| `CLAUDE_CODE_CHILD_SESSION` | `1` when the session was spawned by another session |
| `CLAUDE_EFFORT` | effort level |
| `CLAUDECODE` | `1` |

Note: `CLAUDE_CODE_SESSION_ID`, `CLAUDE_PID`, and `CLAUDE_CODE_CHILD_SESSION`
are **observed but not in the documented env-var list** ... treat as
undocumented surface; conductor prefers the payload `session_id` and falls back
to the env var. `CLAUDE_CODE_CHILD_SESSION=1` is how the registry can tag
helper sessions spawned by `conductor assist`.

### 1.4 Blocking a tool call

**[LIVE]** verified on 2026-07-22, Claude Code v2.1.217. A PreToolUse hook
that exits 0 and prints:

```json
{"hookSpecificOutput": {"hookEventName": "PreToolUse",
  "permissionDecision": "deny",
  "permissionDecisionReason": "LEASE CONFLICT: session a3f2 holds path:*.txt ..."}}
```

blocks the Write: the file was not created, the model received the reason
verbatim and relayed it, and the headless result JSON recorded the denial
under `permission_denials` (tool_name, tool_use_id, tool_input). This is
conductor's lease-enforcement mechanism, proven end to end.

**[DOCS]** Alternatives and details: `permissionDecision` supports
`allow|deny|ask|defer`; exit code 2 with stderr also blocks (stderr fed to
the model); exit codes other than 0/2 are **non-blocking** ... execution
continues. That last fact is the fail-open guarantee: a crashing or missing
lease hook cannot stop work.

### 1.5 Injecting context

**[LIVE]** verified on 2026-07-22, Claude Code v2.1.217. A SessionStart hook
printing `{"hookSpecificOutput": {"hookEventName": "SessionStart",
"additionalContext": "MAGIC_TOKEN_..."}}` delivered the token into the
session's context: the model repeated it verbatim when asked.

**[DOCS]** `additionalContext` is capped at 10,000 characters per value;
UserPromptSubmit supports the same mechanism (context lands alongside the
prompt); SessionStart/UserPromptSubmit also accept plain stdout as context on
exit 0. SessionStart additionally supports `initialUserMessage` ... in `-p`
mode it creates the first turn. This is the D2 hook-injection option and also
how a spawned helper could receive its briefing without a prompt file.

### 1.6 Configuration

**[DOCS]** (confirmed [LIVE] for the project-settings case): hooks live under
`"hooks": {"<Event>": [{"matcher": "...", "hooks": [{"type": "command",
"command": "...", "timeout": <seconds>}]}]}` in `.claude/settings.json`
(project), `.claude/settings.local.json` (project-local), or
`~/.claude/settings.json` (user). `"$CLAUDE_PROJECT_DIR"` works inside the
command string. Per-hook `timeout` exists ... conductor's lease hook will set a
short one and exit 0 on any daemon-unreachable condition (fail open).

---

## 2. Transcripts

### 2.1 Location

**[LIVE]** verified on 2026-07-22, Claude Code v2.1.217, on this machine:

```
~/.claude/projects/<cwd-slug>/<session-id>.jsonl
```

where `<cwd-slug>` is the absolute project path with `/` and `.` replaced by
`-` (e.g. `/Users/x/Workspaces/personal` becomes
`-Users-x-Workspaces-personal`). Files are mode `0600`. The docs do **not**
document this path ... it is observed-only surface, but every hook payload
carries the exact `transcript_path`, so conductor treats the payload as the
source of truth and the slug rule only as a fallback.

### 2.2 JSONL shape

**[LIVE]** verified on 2026-07-22, Claude Code v2.1.217, by schema-probing a
real transcript (keys only, no content retained). One JSON object per line.
Observed `type` values: `user`, `assistant`, `system`, `attachment`,
`file-history-snapshot`, `file-history-delta`, `mode`, `permission-mode`,
`ai-title`, `last-prompt`.

`user`/`assistant` records carry: `sessionId`, `cwd`, `gitBranch`,
`timestamp`, `uuid`, `parentUuid`, `isSidechain`, `version`, `type`, and
`message`. Assistant `message` is a full API message: `model`, `content`
(blocks of `text`, `thinking`, `tool_use`, `tool_result`), `usage`,
`stop_reason`. 

**Design consequence:** the tailer gets, per record, the session id, branch,
cwd, timestamp, and tool calls with arguments ... everything the digest and
briefing composer need, with `gitBranch` refreshed on every record. Sidechain
(subagent) records are flagged, so digests can exclude or fold them.

---

## 3. Headless invocation and resume

Source: https://code.claude.com/docs/en/cli-reference.md and
https://code.claude.com/docs/en/headless.md, plus live runs.

**[LIVE]** verified on 2026-07-22, Claude Code v2.1.217:

- `claude -p "<prompt>" --output-format json` returns a single JSON object
  with `result`, `session_id`, `num_turns`, `total_cost_usd`, full `usage`,
  and `permission_denials`. Exceeding `--max-turns` yields
  `subtype: "error_max_turns"`, `is_error: true`, `result: null`.
- `claude -p --resume <session-id>` resumed a prior headless session **with
  full prior context** (it recalled both an injected token and a file it had
  created) and continued under the **same** session id.
- Nested invocation works: a Claude Code session can spawn `claude -p`; the
  child gets `CLAUDE_CODE_CHILD_SESSION=1`.
- `--allowedTools "<Tool>"` pre-authorizes tools for non-interactive runs;
  `--mcp-config <file>` mounts MCP servers for that run only. In this
  environment `--dangerously-skip-permissions` was blocked by policy; the
  assist spawner must therefore run helpers with explicit `--allowedTools`
  scoping, which is also the safer design.

**[LIVE]** verified on 2026-07-22, Claude Code v2.1.217 (promoted from DOCS for
Phase 4, with the failure cases assist depends on):

- **Happy path:** `claude -p --session-id <uuid>` creates the session under
  exactly that id (the result JSON's `session_id` equals the uuid passed).
- **Collision is a hard error, not a resume or fork:** invoking `--session-id`
  with an id that already exists exits non-zero with
  `Error: Session ID <uuid> is already in use.` A pre-assigned id is
  single-use.
- **A used id cannot be reclaimed:** deleting the transcript JSONL does **not**
  free the id ... a later `--session-id` with it still errors "already in use"
  (the id is tracked more durably than the transcript file). `--resume` of a
  pre-assigned, already-completed id did not resume in this test either.

**Design consequences for assist (Phase 4):** the spawner must mint a **fresh**
uuid per helper and never reuse one; a spawn failure must be treated as
terminal for that id (retry only with a new uuid). Because assist pre-registers
the helper in conductor's registry *before* launching `claude`, any failure
between register and a live process leaves a phantom holding leases ... so
pre-registration must be paired with a guaranteed cleanup (release the
phantom's leases, mark it ended) on every failure path. Both are built and
tested in Phase 4.

**[DOCS]** Also available (not exercised live): `--fork-session` to resume into
a new id, `-c/--continue` for most recent session in cwd, `--input-format
stream-json` for interactive-style headless drive, `--no-session-persistence`,
`--bare`.

---

## 4. MCP

Source: https://code.claude.com/docs/en/mcp.md and mcp-quickstart.md, plus a
live two-session experiment.

### 4.1 Scopes and transports

**[DOCS]** Scopes: local (`~/.claude.json` under the project entry), project
(`.mcp.json`, checked in, requires per-user approval), user (`~/.claude.json`
top level). Precedence local > project > user > plugin > connectors; the
winning entry is used whole, fields are never merged. Transports: stdio,
HTTP (`streamable-http` alias), WebSocket; SSE is deprecated.

### 4.2 D1: do sessions share a stdio server process? **No.**

**[LIVE]** verified on 2026-07-22, Claude Code v2.1.217. A minimal stdio MCP
server that appends `pid= ppid= t=` to a log on startup was mounted via
`--mcp-config` by three sessions (two of them concurrent, started ~650 ms
apart). Result: **three distinct server processes**, each a child of its own
`claude` process:

```
pid=96912 ppid=90151 t=1784735360554   (session A)
pid=97353 ppid=90150 t=1784735361201   (session B, concurrent with A)
pid=59925 ppid=52353 t=1784735486157   (session C, later)
```

**Design consequence (settles D1):** a stdio MCP server is per-session state.
Anything shared across sessions ... registry, inbox, leases ... must live in a
**shared backend** (daemon + SQLite) that per-session stdio shims talk to, or
be an HTTP MCP server all sessions connect to. See DESIGN.md § D1 for the
choice between those two.

### 4.3 Residual

The fixture server answers a manual JSON-RPC handshake correctly
(initialize → tools/list → tools/call returns the pid), but the headless
model runs hit `error_max_turns` before a confirmed tool-call round trip, so
tool invocation through Claude Code was not re-verified with this ad-hoc
fixture. Phase 1 uses the official MCP SDK and carries an integration test
that closes this gap. The per-session-process finding is unaffected ... the
spawn log is written before any handshake.

---

## 5. Summary of design-relevant facts

| Fact | Status |
|---|---|
| PreToolUse `permissionDecision: deny` blocks Edit/Write with a reason the model sees | LIVE-proven |
| Hook exit codes other than 0/2 never block (fail-open is the platform default) | DOCS |
| Every hook payload carries `session_id`, `transcript_path`, `cwd` | LIVE-proven |
| Stop payload carries `last_assistant_message` (consent-free digest source) | LIVE-proven |
| SessionStart/UserPromptSubmit can inject `additionalContext` (10k cap) | LIVE-proven / DOCS |
| Transcripts: `~/.claude/projects/<cwd-slug>/<id>.jsonl`, typed records, sidechain flag | LIVE-proven |
| `--resume <id>` restores full context, same id; `--session-id` pre-assigns an id | LIVE-proven / DOCS |
| Headless JSON result carries `session_id`, cost, `permission_denials` | LIVE-proven |
| One stdio MCP server process **per session** ... shared state needs a shared backend | LIVE-proven (D1) |
| Nested `claude -p` spawn works; child flagged `CLAUDE_CODE_CHILD_SESSION=1` | LIVE-proven |
