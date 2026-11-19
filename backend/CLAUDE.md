# DevBuddy (code folder: Proofix)

Paste a GitHub repo link; DevBuddy finds which user journeys break the app, at how many
users, why, and proves a fix under the same load. Later: a Capacity Diff on every pull request.

## Architecture (target)
- One deterministic, non-AI Spring Boot orchestrator: state machine, budgets, safety limits, approvals.
- Four Gemini agents (Gemini API, NOT Groq): Repository Understanding, Workload Intelligence,
  Performance and Investigation, Fix and Verification.
- Two human approvals: (1) the test plan, (2) the verified fix. Then open a PR.
- Isolated sandbox, k6 load, OpenTelemetry later, resource limits, auto-cancel.
- Principle: measurements decide; Gemini plans and explains. Unconfirmed causes are labelled hypotheses.

## Current state
Java 21, Spring Boot 4.1.2. Auth (JWT), projects, GitHub repo snapshots, Maven/Gradle parsers
(unused), 821 tests. Build 1 (Run model) done: orchestrator skeleton with a fake worker.
Build 2A done: runner keys and the runner API (a separate runner claims and advances its
owner's Runs). No Gemini, sandbox, k6 or investigation yet.

## Run model (`com.proofix.backend.run`, migration V2)
- A Run is one analysis of one repo: QUEUED -> ANALYZING -> AWAITING_PLAN_OK -> TESTING ->
  INVESTIGATING -> AWAITING_FIX_OK -> DONE; FAILED and CANCELLED are also final.
  The allowed moves are in `RunTransitions`; retry = a new Run, never reopen a final one.
- **One rule:** only `RunStateMachine.transition` changes a Run's status (validates, locks
  the row, writes a `run_events` row; illegal move -> 409). `Run.moveTo` and Run's setters
  are package-private; `RunStatusMutationGuardTest` keeps other callers out.
- `run` = model, state machine, `RunLifecycle` (owner-scoped user actions, caps),
  `RunWorkerOps` (locked worker steps). `run.api` = `/api/runs`. `run.worker` = poller,
  reaper, `RunExecutor` and `FakeRunExecutor`.
- `devbuddy.runner.fake.enabled` (default false): turns on the fake worker. When false
  nothing claims Runs. `devbuddy.runner.scheduling.enabled` (false in tests) runs the
  poller and reaper. Limits: `devbuddy.runs.max-active-per-user` (3),
  `devbuddy.runs.max-unfinished-per-user` (10), budgets in `devbuddy.runs.defaults.*`.
- Tests run only against `proofix_test` (`TestDatabaseGuard` refuses anything else).
  Always `mvn clean` before tests.

## Runner keys and runner API (Build 2A, migrations V3 and V4)
- `runnerkey` = `/api/runner-keys` (JWT): create returns the plaintext key ONCE
  (`dbr_` + 43 URL-safe chars); only its SHA-256 hex is stored. Never log, store or return a
  key, its hash or an Authorization header. Cap: `devbuddy.runner-keys.max-active-per-user` (5).
  Every request re-checks the key (revoked or expired -> 401 at once); `last_used_at` <= 1/min.
- `/api/runner/**` has its own security chain (`SecurityConfig`, order 1): runner key (401)
  -> `X-Runner-Version` >= `devbuddy.runner.api.min-version` (426) -> `X-Runner-Instance`
  `^[A-Za-z0-9._-]{1,63}$` (400) -> 64 KB body cap (413). A JWT never works there and a runner
  key never works on user endpoints. None of these filters is a global servlet filter.
- **The key is the security boundary.** The instance id is a correctness label chosen by the
  runner, NOT a credential. `runs.runner_id` = `<keyId>:<instanceId>`; `runs.last_runner_id`
  keeps the last holder (never cleared). Every per-Run call does one locked, owner-scoped
  lookup and checks runner_id (or last_runner_id); anything else -> 404, same body as a
  missing Run. No runner body ever carries a user id.
- Endpoints: `claim` (owner's oldest QUEUED Run only, `SKIP LOCKED`; body none or `{}`),
  `runs/{id}/heartbeat` (no body; `{cancelRequested, status}`), `runs/{id}/report-analysis`
  (strict schema v1 plan, stored redacted with `redactedFields`; commands are untrusted text,
  never executed by the server), `runs/{id}/fail` (`{code, reason}`; reason sanitized,
  redacted, capped at 500).
- **Runner contract:** generate a fresh random instance id on every process start; heartbeat
  every 10 s (reaper timeout 30 s); stop when `cancelRequested` is true or `status` is not the
  phase you are working on.
- **Who wins:** a cancel beats a successful report (pending cancel -> CANCELLED, plan not
  stored); a runner-reported failure beats a pending cancel (stays FAILED, never CANCELLED).
  Report only from ANALYZING, once; otherwise 409.
- `FailureCode` holds the codes and their fix hints (server-side only; a runner never supplies
  hint text). RUNNER_LOST and BUDGET_EXCEEDED are set by the reaper only; /fail rejects them.
  The enum must match `runs_failure_code_check` (`FailureCodeTest`).
- `GET /api/runs/{id}` shows `plan` and `failure {code, reason, fixHint}`; `GET /api/runs` is a
  slim summary (`hasPlan`, `failureCode`), never the plan body.
- **`FakeRunExecutor` is for local development only.** It claims QUEUED Runs of ANY user, so
  it must not run next to the runner API; a startup WARNING says so when both are enabled
  (`devbuddy.runner.api.enabled`, default true).

## Runner (`runner/`, Build 2B-0a)
- Separate Maven module at the repo root (sibling of `backend/`, not a reactor child). Plain
  Java 21, no Spring; deps Jackson, SnakeYAML 2.4 + JUnit 5 only. Build/test with `runner/mvnw`.
  `java -jar runner/target/devbuddy-runner.jar scan <repoPath> [--out facts.json]`.
- **Local-first:** the runner reads the repo on the user's machine; the server never sees code.
  2B-0a does NOT call the server, Gemini or Docker. The backend Maven/Gradle parsers move in 2B-0b.
- `scan` -> `ScanResult` (file names and sizes only) -> `StackDetector` (rule table in
  `RuleTable`, data not branches) -> `ConfigFactsExtractor` (Build 2B-0b-1: VERIFIED Maven and
  Spring Boot config facts with path/line evidence; secret values never stored) -> `facts.json`
  schemaVersion 2 (deterministic: fixed order, LF, no timestamps, no absolute paths). Schema and
  rules: `runner/README.md`.
- **Safety rules (do not weaken):** every path from git or the walk goes through `PathGuard`
  (inside root, never follow symlinks/junctions); `GitProcess` is the only process launcher and
  allows exactly `ls-files`, `rev-parse HEAD`, `status --porcelain`; `MarkerReader` is the only
  content reader (marker files only, <= 2 MB, NOFOLLOW); `.env*` files are reported by path and
  never opened. `PathGuardTest`, `SymlinkSafetyTest` and `SecretNeverLeaksTest` guard these.
- Fixtures: names the root `.gitignore` hides are stored as `__node_modules` / `__target` and
  renamed by `Fixtures.load`; no `.env` is committed (tests write a random canary).

## Rules
- Make no change outside the task I give you; show the plan before editing.
- Never commit secrets. Never touch the database without asking.
- Small commits, tests with each change.
- Scope cuts for now: no security scanner, no Groq, no frontend, no Gradle, no OpenTelemetry.