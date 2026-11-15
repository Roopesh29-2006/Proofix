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
