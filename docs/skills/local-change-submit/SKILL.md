---
name: silk-local-change-submit
description: Use in this repository after local edits when an agent is asked to commit, push, open or update a PR, or prepare a submit handoff. Enforces Silk-specific docs sync, validation reporting, and commit/PR title naming.
---

# Local Change Submit

Use this skill after local edits and before commit, push, PR creation, or PR update.

## Required Checks

Treat documentation sync as part of the same deliverable as the code change.

- If code, scripts, CI, runtime entry points, payload contracts, storage paths, env vars, or validation commands changed, check whether `ARCHITECTURE.md`, `docs/context/**`, `.env.example`, `silk.sh`, or `README.md` became stale.
- If docs are stale, update the closest source-of-truth document in the same commit as the behavior change.
- If code and docs intentionally remain out of sync, record the drift in `docs/context/project/KNOWN_DRIFT.md` and mention it in the PR body.
- For docs-only changes, still check discoverability: update `AGENTS.md`, `ARCHITECTURE.md`, `docs/context/INDEX.md`, or `docs/context/TASK_ROUTER.md` when a new document should be routed to.
- Do not auto-edit `docs/todo-roadmap.md` unless the user explicitly asks.
- Run the narrowest relevant validation, following `docs/context/quality/TEST_MATRIX.md`.
- For docs-only changes, `git diff --check` is usually sufficient.
- In the PR body or final response, state docs sync result and validation commands.

## Naming

Use the repository's existing Conventional Commit style for commit subjects and PR titles:

```text
type(scope): concise verb phrase
```

- Common types: `feat`, `fix`, `docs`, `test`, `ci`, `chore`, `refactor`, `perf`.
- Use a scope when it adds signal: `backend`, `frontend`, `shared`, `web`, `android`, `desktop`, `ci`, `git`, `workflow`, `chat`, `context`.
- Name the behavior, not the process.
- Prefer English subjects, matching recent repo history.
- If the change implements a roadmap item, append the existing TODO id, for example `(TODO-NAV-001)`.
- Avoid vague subjects such as `update docs`, `fix issue`, `codex changes`, or `pr cleanup`.

Good examples:

- `fix(ai): make model HTTP timeout configurable with 5m default`
- `fix(shared): repair desktop websocket silent reconnect on JVM`
- `perf(chat): batch history after WebSocket history_end marker`
- `feat(workflow): implement workflow conversation with auto-activated Claude Code`

## PR Body

Keep it short:

- `Summary`: what changed
- `Validation`: commands run, or why skipped
- `Docs`: docs updated, or docs sync checked with no update needed
