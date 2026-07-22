# Impact-awareness readiness

conductor is the prerequisite side of the impact-awareness feature
(RUNBOOK-15). The joint **Phase 6** builds `ImpactEngine` *after* veridex is
ready — **not here.** This document records the three daemon-reachable seams
Phase 6 will plug into, so it can wire in without spelunking. Each seam is a
thin, read-only projection over existing state; none changes external behavior.

The daemon owns the `Registry` and calls `Briefings`, so all three seams are
reachable from daemon code today (the message seam is already used by the
daemon and the assist spawner).

## Seam (a) — live sessions with repo + branch

Enumerate the non-stale, non-ended sessions currently on the bus, each with the
project repo identity and git branch, to map a changed graph entity to the
sessions working the affected repos.

| | |
|---|---|
| **Provided by** | `Registry.liveSessions()` → `List<Registry.Session>` |
| **File** | `src/main/java/io/github/hhagenbuch/conductor/daemon/Registry.java` |
| **Shape** | Each `Session` carries `projectDir()` (canonical repo identity, from the git common dir) and `gitBranch()`. Filters to `status == "active"` (excludes `stale` and `ended`). |
| **Test** | `RegistryTest.liveSessionsExcludesStaleAndEnded` — proves stale and ended are excluded and repo+branch are present. |

## Seam (b) — a session's recent changed-file set

Get the files a session has touched, from its redacted transcript digest, for
mapping to graph entities.

| | |
|---|---|
| **Provided by** | `Briefings.changedFiles(Registry.Session)` → `List<String>` |
| **File** | `src/main/java/io/github/hhagenbuch/conductor/transcript/Briefings.java` |
| **Shape** | Returns `TranscriptDigest.Digest.filesTouched()` (redacted on ingest). **Consent-gated**: empty list if the session's project has not granted `conductor observe`, or the transcript is missing/unreadable. Never throws, never returns unredacted content. |
| **Test** | `BriefingsChangedFilesTest` — returns files when consented; empty without consent (privacy rule); empty-and-safe on null/missing transcript. |

Note for Phase 6: because this seam is consent-gated, the impact engine must
treat an empty result as "not observable," not "no changes." Sessions in
unconsented projects contribute their registry facts (seam a) but not their
file set.

## Seam (c) — post to a session's inbox from daemon code

Deliver a message to a session's inbox programmatically, without going through
the MCP tool — so the impact engine can notify an affected session directly.

| | |
|---|---|
| **Provided by** | `Registry.post(String from, String to, String body)` → `Optional<String>` (resolved recipient id, or empty if none/ambiguous) |
| **File** | `src/main/java/io/github/hhagenbuch/conductor/daemon/Registry.java` |
| **Shape** | Delivers to a full session id or an unambiguous prefix; the message lands in the recipient's `inbox`. Already used from daemon code (`Daemon.handlePost`) and the assist spawner (`AssistSpawner` posts the "helper has taken…" handoff). |
| **Test** | `RegistryTest.postDeliversByPrefixAndInboxConsumes` and `RegistryTest.ambiguousPrefixIsRejected`; end-to-end in `EndToEndTest`. |

## Status

All three seams exist, are covered by a test, and are reachable from the
daemon. Seams (a) and (b) were added as thin read-only methods in this change;
seam (c) already existed and is used in production paths. No external behavior
changed. Phase 6 (RUNBOOK-15) can depend on these signatures.
