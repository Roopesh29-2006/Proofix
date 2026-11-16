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
(unused), 639 tests. Build 1 (Run model) done: orchestrator skeleton with a fake worker.
No Gemini, sandbox, k6 or investigation yet.

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

## Rules
- Make no change outside the task I give you; show the plan before editing.
- Never commit secrets. Never touch the database without asking.
- Small commits, tests with each change.
- Scope cuts for now: no security scanner, no Groq, no frontend, no Gradle, no OpenTelemetry.