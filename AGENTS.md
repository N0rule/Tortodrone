# Tortodrone agent rules

Java 25 clean-room server/admin for the **unmodified** Spiral Knights client; normal username/password only.

## Start
- Check `git status --short --branch`, recent commits, `VERSION` and **relevant** source/tests. Code and observed stock-client behavior override historical docs.
- Read only task-relevant documentation: `README.md` (quick start), `docs/{architecture,content,operations,development,research}.md`. Do not preload everything or create milestone diary files.
- Make one bounded, reviewable change; preserve unrelated work.

## Never violate
- Never edit installed client/launcher, real preferences, live servers or owner data. Test with disposable data, keys, accounts and processes; stop only processes you started. Do not commit assets, credentials, captures or build/runtime outputs.
- Preserve authentication leases/revisions, ownership, GM authorization, SQLite transactions and rollback. Save before publishing events; reject unsupported DB schemas, never silently migrate.
- Stock protocol contracts require **verified** class/method/argument/callback layouts, mapping order and limits. Do not invent wire fields, mission completions or rewards.
- Scene instances own simulation state. Treat client movement as intent, not coordinates; guard subscriptions/travel generations. Never block on network I/O under storage/simulation locks.
- Current `defaults/` catalogs are real; proposed content packs are **not implemented**. External definitions do not imply gameplay support.

## Finish
- Add focused tests; run applicable checks (ideally `./gradlew build` on JDK 25) and isolated stock-client/graphical tests when behavior requires them. A network fixture cannot prove rendering.
- Review `git diff --check` and exact results; report unverified claims/platforms honestly. Update one canonical doc if behavior changes.
- Do not push, merge, force-update or delete branches without owner authorization. Bump `VERSION` for behavioral releases, not docs-only work.
