# Working on Tortodrone

## Start here

Read this file, [README.md](README.md), and [docs/world-entry-plan.md](docs/world-entry-plan.md)
first. Then inspect `git status --short --branch`, `git log -5 --oneline`, `VERSION`,
and the code relevant to the task. Do not assume the previous thread's checkpoint
still matches the checkout.

[docs/development.md](docs/development.md) explains build, tests and graphical
checks. [docs/server-architecture.md](docs/server-architecture.md) maps the code
and its invariants. Read current research in `docs/server-research.md` only when
needed; obsolete client experiments have been removed. Use Java 25 for builds,
server/admin runtime and persistent tools.

## Rules

- Develop only the Java server and local administration tools. Never modify the
  installed game, game client or launcher. Support normal username/password login
  only; no Steam or guest authentication.
- Preserve real accounts, characters, database and server keys. Tests use temporary
  deployments and disposable accounts/preferences. Never commit secrets, game
  assets, runtime data or build output.
- Persistent tools are Java only; no scripts (Python or otherwise) belong in
  the repository. Run one-off throwaway work from `/tmp`.
- Work autonomously in concrete, manageable milestones. Keep progress updates
  concise; ask only when an important decision needs the owner's input. Do not
  attempt all introductory missions at once or invent completion/reward flags.
- After changes, rebuild and test. Test servers and isolated stock game clients
  may be started; leave every process you started stopped afterward. Never stop
  the owner's server or game session.
- Commit every major completed change with a clear message. Increment `VERSION`
  for an appropriate behavior release, not documentation alone. Preserve unrelated
  changes. Merge/delete branches only when authorized; Git is authoritative.
- Do not migrate existing server data (database, saved characters). Rapid
  development has no real users, so an old database may be refused or discarded
  rather than upgraded.

## Subagent delegation

- Use up to 3 concurrent STEP 5 Preview subagents through OpenCode for repository
  exploration, protocol research, documentation, tests, isolated features,
  straightforward bug fixes, and independent code reviews. Use GPT-6 Luna as
  fallback if STEP 5 Preview is unavailable; do not spawn additional Sol High agents.
- Give each subagent a precise scope, acceptance criteria, and non-overlapping
  file ownership; coordinate shared dependencies and avoid conflicting edits.
- Require concise findings, reuse completed work, and spawn fewer agents when
  tasks are small or dependent on each other.
- Keep architecture decisions, complex concurrency-sensitive changes,
  integration, and final verification with the existing primary agent.

## Workflow

1. Review the current milestone and acceptance limits in `docs/world-entry-plan.md`.
   Reproduce a reported issue before expanding scope.
2. Inspect implementation and existing tests. Verify new wire behavior against
   upstream source and the installed stock reader; never guess packet fields.
3. Implement one milestone with storage/ownership checks and meaningful regression
   coverage. Use `make build test`; add installed-client or graphical checks when
   the change affects their boundary.
4. Review the diff, update current docs, commit, and report what changed,
   checks passed, remaining limitations and final branch/commit. Stop test processes.

The owner authorizes isolated graphical testing without further confirmation.
Follow `docs/development.md`; keep installation and real preferences untouched.
Network tests alone cannot establish that walking animations render.
