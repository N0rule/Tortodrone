# Tortodrone agent instructions

## Start here (small context)

Read this file and [docs/PROJECT.md](docs/PROJECT.md) first. Check `git status --short --branch`, `git log -5 --oneline`, `VERSION`, and relevant source/tests before assuming the documented checkpoint is current. **Do not preload README or other docs.** Use [README](README.md) for deployment, [architecture](docs/server-architecture.md) for deeper invariants and [development](docs/development.md) for testing when relevant. Source and Git are authoritative. Java **25**.

## Rules

- Develop only the Java server and local admin tools. Never alter installed game, client, launcher or their real preferences. Normal username/password only; no Steam/guest auth.
- Protect accounts, characters, SQLite, keys, preferences and running sessions. Use disposable deployments/data for all tests; never stop the owner's processes. Do not commit secrets, game assets, runtime data or build output.
- Persistent repository tools are Java; throwaway probes belong in `/tmp`. Derive protocol and gameplay behavior from upstream evidence and the **unchanged stock client**, never invented wire fields, quests or rewards.
- Work in one verifiable milestone at a time. Preserve unrelated Git edits. Run `make build test` and stock-client/graphical checks when applicable; cleanup only processes you started. Review diffs, commit major finished milestones, bump `VERSION` for behavioral releases only; **do not push, merge or delete branches** without approval.
- No implicit database migration: refuse unsupported schemas without modifying existing data. Ask only for consequential owner decisions.

## Subagents (conserve primary-model usage)

- Prefer **GPT-6 Luna Reasoning low** for bounded repository exploration, protocol research, documentation, focused tests, isolated features, simple fixes and independent reviews—even if the primary agent must wait. Start with one; use up to **3** only for independent scopes. Never spawn extra Sol High agents.
- Give each agent one deliverable, acceptance criteria, source pointers, exact verification and **non-overlapping file ownership**. Research/review is read-only unless specified; no nested delegation or commits unless assigned.
- Primary agent coordinates builds and shared processes (no concurrent builds in one checkout), verifies `file:line` evidence and all diffs, reproduces bugs, integrates and runs final checks. Retain architecture, concurrency, authentication/storage invariants and wire protocol changes with the primary agent. Never let Full Access override data and file boundaries.

## Done criteria

Report changed files, actual test commands/results, limits and final commit. Graphical testing is authorized in the isolated environment described in [development](docs/development.md); network-only tests cannot prove rendering or animations.
