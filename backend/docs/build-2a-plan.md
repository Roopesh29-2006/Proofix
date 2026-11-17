# Build 2A plan: runner keys and the runner API

Approved 2026-11-17. Branch `feature/runner-api` from `main` (06450ea).
A separate program (the "runner") authenticates with a runner key tied to one
user and claims and advances that user's Runs. `RunStateMachine` remains the
only code that changes a Run's status.

## Approved changes to the first draft

1. **Claim scope:** in 2A the runner claim takes **QUEUED Runs only**
   (owner-scoped). Approved TESTING Runs are not claimed, because 2A has no
   endpoint to continue them. Backlog item: "claim approved TESTING Runs once
   an endpoint exists to continue them (Build 2B)". The claim index keeps
   `status IN ('QUEUED','TESTING')` in its predicate, so adding TESTING later
   needs no new index.
2. The global servlet registration of `JwtAuthenticationFilter` is disabled
   (`FilterRegistrationBean.setEnabled(false)`), so it only runs inside its
   own security chain; the same for the runner filter.
3. Tests for the filter split: a JWT on `/api/runner/**` -> 401; a runner key on
   `/api/runs` and `/api/runner-keys` -> 401; the JWT filter does not run on
   runner endpoints and the runner filter does not run on user endpoints.

## Findings from reading the code

- `JwtAuthenticationFilter` is a `@Component` with no `FilterRegistrationBean`,
  so Spring Boot also registered it as a global servlet filter outside the
  chain (masked by `OncePerRequestFilter`). Fixed by change 2.
- `runs.runner_id` is `varchar(100)`; `"<keyId>:<instanceId>"` is
  36 + 1 + <= 63 characters.
- Moving a Run to AWAITING_* clears `runner_id`, so runner calls on AWAITING
  or final Runs naturally fall into the 404 path.
- `spring.jpa.show-sql=true` prints SQL with `?` placeholders, not bound values.

## Migration V3 (`V3__runner_keys.sql`)

- `runner_keys`: `id uuid` PK, `owner_user_id` FK to `users` **ON DELETE
  CASCADE**, `name varchar(60)` non-blank, `key_hash varchar(64)` UNIQUE with
  `CHECK (key_hash ~ '^[0-9a-f]{64}$')` (a plaintext key can never fit),
  `created_at`, `last_used_at`, `revoked_at`, `expires_at` (nullable).
- Index `idx_runner_keys_owner (owner_user_id, created_at DESC)`.
- `runs.recipe jsonb` (nullable).
- `idx_runs_claimable_owner (owner_user_id, created_at) WHERE runner_id IS
  NULL AND status IN ('QUEUED','TESTING')` (backlog #10). The existing
  `idx_runs_claimable` stays for the in-process fake executor.

Key format: `dbr_` + URL-safe base64 (no padding) of 32 `SecureRandom` bytes.
`key_hash` = lowercase hex SHA-256 of the full key string. The plaintext is
returned only in the 201 of `POST /api/runner-keys` (with `Cache-Control:
no-store`) and is never stored, logged or returned again; records holding it
override `toString()`.

## Classes

- `com.proofix.backend.runnerkey`: `RunnerKey` (entity), `RunnerKeyRepository`,
  `RunnerKeyService` (create under the owner's row lock with the active-key cap;
  owner-scoped list and idempotent revoke, another user's key is 404;
  `authenticate(plaintext)` looks the hash up on every request with no cache,
  rejects revoked or expired keys, and updates `last_used_at` at most once a
  minute with a conditional UPDATE), `RunnerKeyController`, request/response
  records, `RunnerKeyProperties` (`devbuddy.runner-keys.max-active-per-user=5`).
- `com.proofix.backend.security`: `RunnerKeyAuthenticationFilter` (only
  `Bearer dbr_...`; anything else, a JWT included, -> 401),
  `RunnerPrincipal(keyId, ownerUserId)`, `RunnerVersionFilter`
  (`X-Runner-Version` semver vs `devbuddy.runner.api.min-version`; missing,
  malformed or older -> 426; runs after auth), `RequestBodyLimitFilter`
  (runner chain only; Content-Length and a counting stream; > 64 KB -> 413).
- `SecurityConfig`: `@Order(1)` chain `securityMatcher("/api/runner/**")` with
  the runner filters only; `@Order(2)` the existing JWT chain.
- `com.proofix.backend.run`: `RunRepository.lockNextQueuedIdForOwner(owner)`
  (`FOR UPDATE SKIP LOCKED`, QUEUED only); `RunWorkerOps` gains owner-scoped
  locked steps `claimNextForOwner`, `heartbeatOwned` (returns cancelRequested),
  `reportAnalysis` (stores the recipe and moves ANALYZING -> AWAITING_PLAN_OK
  under one lock; a pending cancel wins: CANCELLED, recipe not stored),
  `failOwned`. `Run.setRecipe` is package-private.
- `com.proofix.backend.run.runner`: `RunnerApiController`, `RunnerRecipe` +
  `StrictRecipeParser` (dedicated strict mapper: unknown properties, nulls,
  coercion, trailing tokens and duplicate keys rejected; then field limits),
  `FailReasonSanitizer` (control chars stripped, whitespace collapsed,
  `SecretRedactor` high-confidence patterns, cap 500, non-blank).
- `RunnerApiSafetyCheck`: startup WARN when `devbuddy.runner.fake.enabled` and
  `devbuddy.runner.api.enabled` (default true) are both on.

## Endpoints

| Endpoint | Auth | Result |
|---|---|---|
| `POST /api/runner-keys {name}` | JWT | 201 with the key once; 409 over cap; 400 bad name |
| `GET /api/runner-keys` | JWT | own keys, no secret or hash |
| `POST /api/runner-keys/{id}/revoke` | JWT | 200; 404 if missing or another user's |
| `POST /api/runner/claim {instanceId}` | key | 200 `{runId, repoUrl, status}` or 204; `instanceId` `^[A-Za-z0-9._-]{1,63}$` |
| `POST /api/runner/runs/{id}/heartbeat` | key | 200 `{cancelRequested}` |
| `POST /api/runner/runs/{id}/report-analysis {recipe}` | key | 200 `{status}` (AWAITING_PLAN_OK, or CANCELLED when a cancel won); 409 if not ANALYZING |
| `POST /api/runner/runs/{id}/fail {reason}` | key | 200 `{status: FAILED}` |

Every runner call on a Run not owned by the key's user and held by this
`keyId:instanceId` -> 404 `{"error":"Run not found"}`. No runner body accepts a
user id; unknown fields are rejected on runner bodies.

## Recipe schema (strict, body <= 64 KB)

`stack` (string <= 50), `buildCommand`, `startCommand` (strings <= 500,
untrusted text, never executed), `port` (1..65535), `envVarNames` (<= 50 names
matching `^[A-Z][A-Z0-9_]*$`, names only), `needsDatabase` (string <= 50),
`evidence` (<= 20 strings <= 200). Anything else is rejected.

## Tests (written first; `mvn clean`; only `proofix_test`)

- Key service/API: plaintext once, only the hash stored, list without secrets,
  cap (incl. concurrent), revoke of another user's key 404, revoke blocks the
  next request, expired key 401, `last_used_at` throttled.
- Plaintext not persisted: every row of every public table cast to text is
  searched for the key; captured logs contain neither key nor hash.
- Auth chains: change 3 above; version header 426; body > 64 KB 413.
- Isolation: runner A cannot claim, heartbeat, report or fail user B's Run
  (404 each, same body as a missing Run); also for another instance of the
  same key.
- Concurrency: two runners of one owner never claim the same Run; claim
  returns only own Runs; QUEUED only (an approved TESTING Run is not claimed).
- Heartbeat returns cancelRequested; cancel beats report-analysis.
- Recipe: every rejection class. Startup warning. Mutation guard still passes.
- Mutation checks (applied by hand, reverted, reported): remove the owner
  filter in claim; remove the revoked check; store plaintext instead of hash.

## Commits (stop for approval after each)

1. V3, key service, user endpoints, JWT filter registration fix, tests, this
   plan. Mutation check 3.
2. Runner auth filter, version and body-limit filters, chains, owner-scoped
   claim, auth/isolation/concurrency tests. Mutation checks 1 and 2.
3. Heartbeat, report-analysis, fail, recipe parser, reason sanitizer, caps,
   startup warning, docs, CLAUDE.md, backlog (#10 done; TESTING-claim item).

No Flyway against `Proofix_db` without telling the user first; V3 reaches
`proofix_test` through the tests only.
