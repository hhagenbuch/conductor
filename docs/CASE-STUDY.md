# Case study: two RFCs, coordinated

conductor dogfooded on real work. Two RFC repos —
[hindsight](https://github.com/hhagenbuch/hindsight) (Runbook 11) and
[agent-attest](https://github.com/hhagenbuch/agent-attest) (Runbook 12) — were
authored as **two conductor-coordinated sessions** and their PRs opened for
review. This is the record of what the coordination actually did, written from
the real bus logs, **including the overhead and the parts that went wrong.**

Timing claims get checked, so read §5 with §6: this measures **one coordinated
authoring session**, not a multi-day RFC week, and it says so.

## 1. Setup (what actually ran)

- A conductor daemon on `127.0.0.1`, a SQLite registry, real hooks-style
  registration over the HTTP API.
- **Session A — `hindsight-worker`** (this operator): registered on the
  hindsight repo, claimed `repo:` (lease #1), authored `docs/RFC.md`.
- **Session B — the agent-attest worker**: a real, separate headless
  `claude -p --model sonnet` process, registered on the agent-attest repo,
  claimed `repo:` (lease #3), authored `docs/RFC.md`, and reported back over
  the bus — all while Session A worked.
- The two sessions coordinated over the bus: `who_else` awareness, `post` /
  `inbox` messages, and a lease on the **shared** profile/diagram resource both
  RFCs wanted to touch.

## 2. What was coordinated

**Mutual awareness.** Both sessions were visible to each other in `conductor ps`
with their project, branch, and `repo:` lease from the moment they registered.
Neither was working blind.

**The shared resource, protected by a lease.** Both RFCs end by adding
themselves to the platform diagram in the profile repo — a classic
two-sessions-one-file collision. Session A claimed `branch:profile-update`
(lease #4, note: *"owning both profile+diagram updates so we do not collide"*)
and posted the intent. Session B **acknowledged over the bus** — verbatim from
the message log:

> *ack — you own the profile update; I will send you my one-liner for the
> platform diagram instead of editing it.*

That is the whole point of the tool: the collision that caused a real incident
in this portfolio's history was prevented here by an explicit lease plus one
message, before either session touched the shared file.

**Three real bus messages** carried the coordination (from the message log):
1. A → B: "hindsight RFC drafted + PR open; both our RFCs cite the shared
   platform diagram — I will claim the profile update…"
2. B → A: the ack above.
3. B → A: "agent-attest draft is done: docs/RFC.md written (ABoB field-source
   table, in-toto/DSSE chain … three-legged stool … sigstore keyless)…"

## 3. What conflicted, and what the leases prevented

- **The shared profile update** was the one genuine contention point, and the
  `branch:profile-update` lease + the ack resolved it cleanly: one owner, the
  other sends a diff. No double-edit, no lost work.
- **The two `repo:` leases did not conflict** — the sessions were in different
  repos, so their scopes were disjoint by construction. This is an honest,
  slightly deflating finding: for *cross-repo* coordination the leases are
  mostly *informational* (who owns what), and the collision-prevention teeth
  (the PreToolUse enforcement hook) only bite when two sessions share a repo.
  The enforcement demo (`docs/war-story.gif`) is the same-repo case; this
  dogfood is the cross-repo case, and it leaned on awareness + messaging +
  the shared-resource lease rather than on a blocked edit.

## 4. What went wrong (the honest part)

- **The first agent-attest spawn failed outright.** It was launched with a
  friendly session id (`agent-attest-worker`); Claude Code rejected it —
  `Error: Invalid session ID. Must be a valid UUID.` This is exactly the
  constraint [`GROUND-TRUTH.md`](GROUND-TRUTH.md) §3 records for `--session-id`,
  and `conductor assist` already honors it by minting a UUID. The lesson: the
  **manual** cross-repo path has to mint UUIDs too. The retry with a real UUID
  succeeded.
- **A phantom lease was cleaned up automatically.** The failed worker had
  already registered and claimed `repo:` (lease #2). Ending that session
  released the lease — the release-on-`SessionEnd` behavior, observed in the
  wild rather than in a unit test. Without it, `conductor ps` would have shown
  an orphan lease held by a dead session.
- **`assist` did not fit this job, and that is a real boundary.** `conductor
  assist` spawns a helper in a **git worktree of the parent's repo** — it is
  built for *same-repo* parallelism (take the test suite while I write the
  source). hindsight and agent-attest are **different repos**, so the second
  session was launched as a plain coordinated `claude -p` registered on the bus,
  not via `assist`. The runbook's Phase 5 language ("use `conductor assist` to
  launch the agent-attest worker") assumed a fit that does not exist. The
  finding for the design: **assist = intra-repo; the bus = inter-repo.** A
  future `assist --repo <other>` mode is a real feature, not a bug fix.
- **The transcript/briefing tier was not exercised.** No project granted
  `conductor observe` consent in this run, so briefings would have been
  registry-only. The parallelism did not need the digest tier; a fuller dogfood
  (a joining helper mid-task) would.

## 5. Timing (measured)

Wall-clock, from the run's own timestamps:

| | authored | wall-clock | cost | turns |
|---|---|---|---|---|
| hindsight RFC (Session A, this operator) | direct | **87 s** | — | — |
| agent-attest RFC (Session B, real `claude -p` sonnet) | autonomous | **297 s** | **$1.11** | 34 |

Session B ran **in the background while Session A wrote hindsight.** So the
coordinated wall-clock to produce **both** RFCs was bounded by the slower
session:

- **Sequential** (one after the other): 87 + 297 ≈ **384 s**
- **Coordinated / parallel**: max(87, 297) ≈ **297 s**
- **≈ 23% wall-clock reduction** on this bounded task — and the *unit of work
  doubled* (two RFCs, not one) for that time.

That is the honest shape of "finish faster": not a single job done in half the
time, but **two independent jobs completing in the time of the longer one**,
with the shared-resource collision prevented along the way.

## 6. Honest limits of this measurement

- **This is one authoring session, not a week.** The runbooks are RFC-first
  with their own RFC-review STOP points; the RFCs are now open for review
  ([hindsight #1](https://github.com/hhagenbuch/hindsight/pull/1),
  [agent-attest #1](https://github.com/hhagenbuch/agent-attest/pull/1)), not
  merged. The "agent-slo solo RFC week" is the documented solo precedent, but
  this dogfood is **not** a controlled A/B against it — it is a single
  coordinated run, and the 23% figure is for that run only.
- **The two sessions are not symmetric.** Session A is the operator writing
  directly (fast); Session B is an autonomous helper (slower, and it cost real
  money — $1.11). A fair "two humans" comparison would look different; this is
  "operator + spawned helper," which is conductor's actual assist model.
- **Overhead is real and counted.** One failed spawn (a full retry), plus a few
  seconds each of register/claim setup. On a 5-minute task that overhead is a
  rounding error; on a 30-second task it would dominate. conductor earns its
  keep on work long enough that a collision would hurt.

## 7. What the dogfood changed

- Confirmed the design's spine end-to-end on real work: register → claim →
  coordinate → integrate-via-PR, with release-on-end cleaning a phantom lease.
- Surfaced two concrete design items, now written down: **assist is intra-repo**
  (an `--repo` mode is the inter-repo follow-on), and **manual coordinated
  spawns must mint UUIDs** (assist already does; document the manual path).
- Produced two real RFCs and their review PRs as a side effect of testing the
  coordinator — which is the most credible kind of dogfood: the tool earned its
  place by helping ship the next thing.

---

*Both RFCs remain open for review; the timing claims above are from this single
coordinated run and are scoped accordingly.*
