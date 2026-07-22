# conductor

Session orchestration for Claude Code: sessions that are aware of each other,
talk to each other, and coordinate on shared projects.

**Thesis:** sessions are workers; work needs a bus. Claude Code coordinates
subagents *within* a session and nothing *across* sessions ... no awareness, no
messaging, no conflict prevention, no way to throw a second session at a job in
progress. Conductor is that missing layer, built from three mechanics Claude
Code already exposes: an **MCP server** for the bus, **hooks** for
registration and enforcement, and **transcripts** for awareness.

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

![Two sessions in one repo. The second is blocked twice — once for a conflicting file edit, once for a conflicting git merge — each time with a message naming the holder and the way out.](docs/war-story.gif)

<details>
<summary>Transcript of the recording</summary>

```console
$ conductor ps
SESSION    STATUS   PROJECT                BRANCH   SEEN
a3f2c118   active   …/tmp.v2yiirQc5s       main     0s ago
b7e19d04   active   …/tmp.v2yiirQc5s       main     0s ago

LEASE  SCOPE                HOLDER     EXPIRES
#1     path:src/main/**     a3f2c118   in 59m
#2     branch:main          a3f2c118   in 59m

── Act 1 ── session b7e19d04 tries to EDIT a file a3f2 has leased:
  🚫 LEASE CONFLICT: session a3f2c118 (task: refactoring the parser) holds
     path:src/main/**; the file src/main/Parser.java is covered by
     path:src/main/**. The lease expires in ~59m. Coordinate first: use
     conductor `post` to message a3f2c118, or `claim` disjoint work. Do not
     edit here until they release it.

── Act 2 ── the same session tries a conflicting `git merge` (the stale-main incident):
  🚫 LEASE CONFLICT: session a3f2c118 (task: preparing the release merge) holds
     branch:main; a `git merge` touches branch main, leased as branch:main. The
     lease expires in ~59m. Coordinate first: use conductor `post` to message
     a3f2c118, or `claim` disjoint work. Do not edit here until they release it.
```

</details>

The recording is scripted, not hand-captured:
[`demo/war-story.tape`](demo/war-story.tape) drives
[`demo/war-story.sh`](demo/war-story.sh) with
[vhs](https://github.com/charmbracelet/vhs) against the real conductor daemon
and the real PreToolUse enforcement hook, so `vhs demo/war-story.tape`
reproduces it from a clean clone. No network, no API key.

## Architecture

One daemon per machine owns a SQLite registry behind a localhost HTTP API.
Sessions touch it two ways: **hooks** (installed by `conductor init`) call the
daemon on session lifecycle and before every mutating tool call, and a
per-session **stdio MCP shim** gives the session the bus tools. A stdio MCP
server is spawned once per session (verified — see
[docs/GROUND-TRUTH.md](docs/GROUND-TRUTH.md) §4.2), so shared state has to live
in the daemon, not the shim.

```mermaid
flowchart LR
    subgraph sessions["Claude Code sessions"]
      A["session A"]
      B["session B"]
    end

    A -- "stdio MCP" --> SHIM_A["shim"]
    B -- "stdio MCP" --> SHIM_B["shim"]
    A -. "hooks" .-> HOOK_A["init'd hooks"]
    B -. "hooks" .-> HOOK_B["init'd hooks"]

    SHIM_A -- "localhost HTTP" --> D
    SHIM_B -- "localhost HTTP" --> D
    HOOK_A -- "register · heartbeat · enforce" --> D
    HOOK_B -- "register · heartbeat · enforce" --> D

    subgraph daemon["conductor daemon (one per machine)"]
      D["HTTP API"] --> REG[("SQLite:<br/>sessions · messages · leases")]
    end

    D -. "who_else · post/inbox · claim/release/leases" .-> SHIM_A
    D -. "PreToolUse allow/deny" .-> HOOK_B
```

- **Registry** — sessions (id, project, branch, worktree, task, freshness,
  redacted activity digest) and messages. Past-TTL sessions show `stale`,
  never vanish.
- **Leases** — advisory locks on `repo:` / `path:<glob>` / `branch:<name>`
  scopes, with a TTL. A **PreToolUse hook** on `Write`/`Edit`/`Bash` checks
  them and blocks a conflicting write or history-moving git command with a
  message naming the holder.
- **Fail-open** — if the daemon is down, hooks exit 0 (never block a tool),
  bus tools return "unavailable", and `ps` says loudly that leases are
  unenforced. A dead coordinator must not stop work.
- **Authority boundary** — conductor never edits code, never merges, never
  approves. It schedules, informs, and blocks conflicting writes ... the same
  boundary as agent-medic's Surgeon: system-wide visibility earns observation
  and veto, never a pen.

## Quickstart

```console
$ mvn -DskipTests package
$ java -jar target/conductor.jar init          # install hooks into ./.claude/settings.local.json
$ java -jar target/conductor.jar ps            # who's on this project, and what leases are held
```

`init` writes into `.claude/settings.local.json` (per-user; Claude Code
gitignores it) so conductor is never committed onto a teammate. It is
**additive** to your existing hooks, and `conductor remove` strips exactly
conductor's entries.

Register the MCP shim once (user scope covers every project):

```console
$ claude mcp add-json --scope user conductor \
    '{"type":"stdio","command":"java","args":["-jar","'"$PWD"'/target/conductor.jar","mcp-shim"]}'
```

Then, inside any session in that project, the bus tools are available:
`who_else`, `post` / `inbox`, `claim` / `release` / `leases`, and `brief_me`.
Add this to the project's `CLAUDE.md` so sessions check in at the natural
boundaries (conductor ships polling-first — no magic context injection):

```markdown
## Coordinating with other sessions (conductor)
- At task start, and before any commit, PR, or merge: call `who_else`, then
  `inbox`. Before editing shared files, `claim` the scope; if another session
  holds it, coordinate over `post`.
```

### Awareness and briefings (opt-in)

`who_else` and `ps` report each session's stated task and, if you opt in, a
redacted digest of what it is actually doing. Observation is **consent-gated,
per project, per user, per machine**:

```console
$ java -jar target/conductor.jar observe        # consent for THIS project (local-only, git-ignored)
```

`observe` writes a `.conductor-consent` file and adds it to the repo's
`.git/info/exclude` itself, so it can never be committed onto another
contributor — consent to read your local transcripts is yours alone to give
(`--revoke` withdraws it). With consent, the daemon tails the registered
sessions' transcripts, **redacting on ingest** with the
[agent-blackbox](https://github.com/hhagenbuch/agent-blackbox) redaction
library, and marks those sessions `observed` in `ps`/`who_else`. Without it,
sessions are registry-only.

`brief_me` distills a session's registry entry, its redacted digest, and its
leases into a **briefing bundle** — the context a joining helper needs without
sharing raw conversation. Each bundle is stamped with when it was generated and
how fresh the parent was, and it tells the reader to re-brief before any
decision that depends on the parent's current state: a briefing is a snapshot.

## Spawning a helper (`assist`)

![conductor assist hands a lease-disjoint slice to a briefed helper that works in its own worktree and integrates via PR](docs/assist.gif)

<details>
<summary>Transcript of the recording</summary>

```console
$ conductor ps    # one session, holding src/main while it writes the retry logic
SESSION    STATUS   PROJECT     BRANCH   SEEN
a3f2c118   active   …/widget    main     2s ago
LEASE  #1  path:src/main/**   a3f2c118   in 59m

$ conductor assist a3f2c118 --task 'take the test suite' --claim path:src/test/** --allow Write,Bash
conductor: spawned helper 3b4149b3
  branch:   feature/assist-3b4149b3
  worktree: …/widget-assist-3b4149b3
  briefing: …/widget-assist-3b4149b3/.conductor-briefing.md

$ conductor ps    # the helper is a child on its own branch, holding its own scope
a3f2c118   active   …/widget   main                      11s ago
3b4149b3   active   …/widget   feature/assist-3b4149b3    5s ago  child
LEASE  #1 path:src/main/**  a3f2c118      #2 path:src/test/**  3b4149b3

Parent's inbox:
  • helper 3b4149b3 has taken: write the retry test suite (branch feature/assist-3b4149b3)…
  • on it — read the briefing, taking the test suite in my worktree
  • done: test suite committed on my branch; opening a PR (stayed in path:src/test/**, no conflict with your src/main work)

Helper committed on its OWN branch (PR-ready); the parent tree is untouched.
```

</details>

`conductor assist <parent-session-id> --task "…" --claim <scope>… --allow Tool,Tool`
mints a **fresh** session id (a used `--session-id` can never be reclaimed —
[GROUND-TRUTH.md](docs/GROUND-TRUTH.md) §3), creates a git worktree on a new
`feature/` branch, pre-registers the helper and its leases, writes it a briefing
bundle, and launches a headless `claude -p` scoped with `--allowedTools`. The
helper works only in its worktree and integrates via PR — it never edits the
parent's tree, which retires the stale-local-main incident class structurally.
Every failure path releases the helper's leases and removes the worktree, so a
spawn that dies mid-launch leaves no phantom holding scopes.

> The recording drives the real daemon, registry, leases, worktree, briefing,
> and inbox; only the helper's leaf process is scripted (in place of a real
> `claude -p`) so it reproduces offline. It makes **no timing claim** — the
> finish-faster measurement is done honestly in `docs/CASE-STUDY.md` from a real
> coordinated week (Phase 5).

## Roadmap

- [x] **Phase 0** — ground truth + design ([GROUND-TRUTH.md](docs/GROUND-TRUTH.md), [DESIGN.md](docs/DESIGN.md))
- [x] **Phase 1** — bus + registry + hooks: `who_else` / `post` / `inbox`, `conductor init` / `ps`, two sessions mutually visible
- [x] **Phase 2** — leases + fail-open enforcement: `claim` / `release` / `leases`, PreToolUse block on Write/Edit and history-moving git (the war-story GIF)
- [x] **Phase 3** — transcript awareness + briefing: consent flow (`conductor observe`), redacted digests, `brief_me`
- [x] **Phase 4** — `assist`: worktree + headless helper spawn with a briefing bundle, PR-based integration
- [ ] **Phase 5** — dogfood + case study from a real coordinated week

## Contract

The bus's MCP surface is contract-tested with
[mcp-pact](https://github.com/hhagenbuch/mcp-pact) in CI
([pacts/conductor-bus.mcp-pact.json](pacts/conductor-bus.mcp-pact.json)):
renaming or reshaping any bus tool becomes a red build.
