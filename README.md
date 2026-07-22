# conductor

Session orchestration for Claude Code: sessions aware of each other, talking to
each other, coordinating on shared projects ... with one command that spawns a
helper session to finish a running job faster.

**Thesis:** sessions are workers; work needs a bus. Claude Code has subagents
*within* a session; nothing coordinates *across* sessions.

Status: Phase 0 (ground truth + design). See `docs/GROUND-TRUTH.md` and
`docs/DESIGN.md`.
