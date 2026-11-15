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
(unused), 117 tests. No Gemini, sandbox, k6, orchestrator or investigation yet.

## Rules
- Make no change outside the task I give you; show the plan before editing.
- Never commit secrets. Never touch the database without asking.
- Small commits, tests with each change.
- Scope cuts for now: no security scanner, no Groq, no frontend, no Gradle, no OpenTelemetry.