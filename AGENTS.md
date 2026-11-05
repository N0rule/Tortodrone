# Tortodrone agent rules

Java 25 clean-room server for the **unmodified** Spiral Knights client, with local
console and optional WebUI administration. Accounts use normal username/password
only.

## Current shape
- One executable `tortodrone.jar` beside `config/`, `content/`, `webui/` and
  `plugins/`. `data/` holds private runtime state (SQLite, keys, logs and the
  client-artwork cache) and sits beside the JAR.
- Hosting is headless. `--gui` is rejected; use the server terminal and the
  optional WebUI under `/admin/`. There is no separate admin JAR or desktop
  server UI.
- Defaults: game TCP `47624`, shared WebUI/News HTTP `8080`
  (`webui/content_url-port`). News is public; WebUI administration requires an
  identity, while the local console is trusted.
- SQLite schema 16 uses WAL and atomic transactions. Known schemas 9–15 require
  the explicit offline upgrade; unknown schemas are refused without migration.
- Players connect with the unmodified client through KnightLauncherCustom and the
  public key in `data/keys/public.txt`. Never share `data/keys/`.
- Content is unified YAML under `content/`; the legacy pack reader is
  migration-only. Imported definitions alone do not prove gameplay support.

## Start
- Check `git status --short --branch`, recent commits, `VERSION` and **relevant**
  source/tests. Code, current configuration and observed stock-client behavior
  override older docs.
- Read only task-relevant pages. Canonical docs: `README.md` (setup),
  `docs/operations.md` (hosting, WebUI and backups), `docs/commands.md`
  (commands), `docs/content.md` (content and economy), `docs/architecture.md`
  (internals), and `docs/development.md` (verification). `docs/provenance.md`
  and `CONTRIBUTING.md` cover licensing and contribution. Do not preload all
  docs or create milestone diaries.
- Change only what the request needs; preserve unrelated Git edits, runtime state
  and working behavior.

## Never violate
- Never modify the installed client/launcher, real preferences, running server or
  owner data. Use disposable test keys/accounts/databases/processes; stop only
  processes you started. Do not commit secrets, client assets, captures,
  unlicensed material or build outputs.
- Preserve authenticated ownership/leases/revisions, GM authorization, atomic
  SQLite writes/rollback and commit-before-publish. Reject unknown database
  schemas without automatic migration.
- Verify native protocol class/method/argument/callback layouts and mappings. Do
  not invent packets, quests or rewards.
- Scene instances own simulation state; client input is intent, not position.
  Guard travel/subscription generations; keep network I/O outside
  simulation/storage locks.
- Keep the single-JAR and headless administration contract. Do not reintroduce
  removed desktop/admin-JAR paths or move runtime state away from `data/`.

## Verification — test only what the change needs
- **Never run the full test suite by default.** `./gradlew testAll` is very slow
  and expensive; do not run it for routine changes, and do not treat "major
  change" as permission. Run it only when the owner explicitly asks for it.
- **Docs/minor cleanup:** inspect diff/links; no Java tests.
- **Normal Java/content change:** `./gradlew testUnit
  -PunitTests=AffectedTest,OtherAffectedTest` compiles/packages and runs only the
  selected standalone programs (see `docs/development.md`). Use `./gradlew build`
  for compilation/package checks without execution. Add a focused regression if
  behavior changed.
- **Auth/storage/trade/protocol/concurrency:** targeted positive/negative,
  ownership and rollback tests; add relevant integration checks. Do not trade
  away safety for speed.
- **Client-visible changes:** focused tests first. Run **one relevant isolated
  stock-client/graphical scenario** only when required to establish the changed
  behavior; otherwise state that visuals were not verified. Never run graphical
  tests routinely.
- Pick the smallest set of tests that actually covers the change; never widen to
  unrelated areas. Do not run benchmarks, repeated builds or tests for code you
  did not touch.
- Stop after acceptance criteria pass. Rerun checks only for affected
  changes/failures; report skipped or unavailable verification honestly.

## Finish
- Review `git diff --check`, changed files and **actual** test results; update one
  canonical doc only when needed.
- **Commit completed work** when acceptance criteria pass: stage the change set
  and make one descriptive commit on the current branch. Uncommitted finished work
  is lost work; do not leave a checkpoint uncommitted unless the owner asked for
  a dirty tree.
- Do not push, merge, force-update or delete branches without owner approval. Bump
  `VERSION` for behavioral releases, not docs-only changes.
- This project is heavily AI-assisted. Review and understand every generated
  change; do not commit output you have not checked, and report only verification
  actually performed.
