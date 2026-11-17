# Migration backlog

Schema changes to make in future Flyway migrations (V2+). Never edit
`V1__baseline.sql`; it mirrors the schema that already exists in `Proofix_db`.

## Integrity fixes found in the V1 baseline review

1. **`updated_at` triggers.** No table has a trigger that maintains `updated_at`,
   and the entities map it as `insertable=false, updatable=false`, so the value
   never changes after insert. Add one shared trigger function and attach it to
   every table with an `updated_at` column (`users`, `projects`, `projects_repos`,
   `github_connections`, `project_runtime_config`, `test_configurations`,
   `remediation_proposals`), then mark the entity fields as DB-generated on update.
2. **Unique active project name per user.** Duplicate names are only blocked in
   application code (race-prone). Add a partial unique index, e.g.
   `UNIQUE (user_id, lower(project_name)) WHERE deleted_at IS NULL`, and map the
   violation to 409 Conflict.
3. **`github_connections.user_id` uniqueness.** The entity declares
   `unique = true` but the database only has a non-unique index plus the composite
   `uq_github_connection (user_id, github_connection_id)`. Decide whether a user may
   have one GitHub connection or several, then make the entity and the database
   agree (partial unique index on active connections, or drop `unique = true`).

## Missing tables for the DevBuddy architecture

Not present in V1; design before implementing the matching features:

- **Architecture map**: the Repository Understanding agent's output per snapshot
  (components, endpoints, data stores, dependencies), with evidence references.
- **User journeys**: the Workload Intelligence agent's journeys per project/snapshot,
  their steps and their traffic weights.
- **Breaking points**: per test run and journey, the load at which it broke
  (users/RPS), the failing metric and threshold, and whether the cause is a
  measured fact or a labelled hypothesis.
- **Approvals**: the two human approvals (test plan, verified fix) with approver,
  decision, timestamp, the exact artifact version approved, and comments.

## Run model follow-ups (found while building V2 `runs`)

4. **`OPENING_PR` state.** CLAUDE.md says the verified fix is followed by
   opening a PR, but approving at `AWAITING_FIX_OK` currently goes straight to
   `DONE`. Add `OPENING_PR` (`AWAITING_FIX_OK -> OPENING_PR -> DONE | FAILED |
   CANCELLED`) to `RunStatus`, `RunTransitions` and the transition tests, and
   widen the `runs_status_check`, `run_events_*_status_check` and (if it becomes
   worker-driven) the active-state CHECK/partial indexes in a new migration.
5. **AWAITING expiry.** Runs in `AWAITING_PLAN_OK` / `AWAITING_FIX_OK` wait
   forever. They do not count toward the per-user cap (QUEUED + active only),
   so a user can accumulate any number of them, and approving several at once
   puts them all into TESTING above the cap. Add a
   configurable expiry (e.g. 7 days since entering the state) enforced by the
   reaper, moving them to `CANCELLED` with a `SYSTEM` event. Needs an index on
   `updated_at WHERE status IN ('AWAITING_PLAN_OK','AWAITING_FIX_OK')`.
6. **Link `test_runs` to Runs.** When the TESTING phase starts producing real
   load tests, add a nullable `test_runs.run_id` FK to `runs(id)` instead of
   replacing `test_runs` (decided in Build 1: Runs sit beside it).

## Runner API follow-ups (found while building Build 2A)

7. **Claim approved TESTING Runs (Build 2B).** 2A claims QUEUED only, because
   nothing continues a TESTING Run yet. Once an endpoint does, widen the claim
   to `status IN ('QUEUED','TESTING')`; `idx_runs_claimable_owner` already
   covers both.
8. **Plan revise loop.** Let the user send a plan back for revision
   (`AWAITING_PLAN_OK -> ANALYZING`) instead of only approve or reject. Needs
   versioned plans (a `run_plans` table keyed by Run and version, the approved
   version recorded on approval) and a new transition.
9. **Live flow endpoint (Build 2B).** `POST /api/runner/runs/{id}/flow` for the
   dashboard "angiography" view: per journey step, requests, errors, p95
   latency and in-flight virtual users, sent periodically during TESTING.
   Needs a storage decision (rolling window vs. table) and its own size caps.
10. **Server-side failure codes for other system failures.** The fake worker's
    "worker error" (and any future server-side failure) has no `failure_code`,
    so the owner sees no fix hint. Add codes as they become real.
