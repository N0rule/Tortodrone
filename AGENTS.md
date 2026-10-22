# Tortodrone agent rules

Java 25 clean-room server/admin for the **unmodified** Spiral Knights client; normal username/password only.

## Start
- Check `git status --short --branch`, recent commits, `VERSION` and **relevant** source/tests. Code and observed stock-client behavior override older docs.
- Read only task-relevant pages (`README.md`, `docs/{architecture,content,operations,development,research}.md`). Do not preload all docs or create milestone diaries.
- Change only what the request needs; preserve unrelated Git edits, runtime state and working behavior.

## Never violate
- Never modify the installed client/launcher, real preferences, running server, or owner data. Use disposable test keys/accounts/databases/processes; stop only processes you started. Do not commit secrets, client assets, captures or build outputs.
- Preserve authenticated ownership/leases/revisions, GM authorization, atomic SQLite writes/rollback and commit-before-publish. Reject unknown DB schemas without automatic migration.
- Verify native protocol class/method/argument/callback layouts and mappings. Do not invent packets, quests or rewards.
- Scene instances own simulation state; client input is intent, not position. Guard travel/subscription generations; keep network I/O outside simulation/storage locks.
- Unified YAML content and a migration-only legacy pack reader are implemented; imported definitions alone do not prove gameplay support.

## Verification — fast by default
- **Docs/minor cleanup:** inspect diff/links; no Java tests.
- **Normal Java/content change:** `./gradlew testUnit -PunitTests=AffectedTest,OtherAffectedTest` compiles/packages and runs only the selected standalone programs (see `docs/development.md`). Use `./gradlew build` for compilation/package checks without execution. Add a focused regression if behavior changed.
- **Auth/storage/trade/protocol/concurrency:** targeted positive/negative, ownership and rollback tests; add relevant integration checks. Do not trade away safety for speed.
- **Client-visible changes:** focused tests first. Run **one relevant isolated stock-client/graphical scenario** only when required to establish the changed behavior; otherwise state that visuals were not verified. Never run graphical tests routinely.
- **Major cross-cutting change or explicit release verification:** full `./gradlew testAll` at the final checkpoint, not after each edit. A routine patch `VERSION` bump alone does not require the full suite. Do not run benchmarks, full suites, repeated builds or unrelated checks by default.
- Stop after acceptance criteria pass. Rerun checks only for affected changes/failures; report skipped or unavailable verification honestly.

## Finish
- Review `git diff --check`, changed files and **actual** test results; update one canonical doc only when needed.
- **Commit completed work** when acceptance criteria pass: stage the change set and make one descriptive commit on the current branch. Uncommitted finished work is lost work; do not leave a checkpoint uncommitted unless the owner asked for a dirty tree.
- Do not push, merge, force-update or delete branches without owner approval. Bump `VERSION` for behavioral releases, not docs-only changes.
