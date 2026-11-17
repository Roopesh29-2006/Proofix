# Security backlog

Follow-ups found while hardening repository analysis. Not yet scheduled.

## Secret redaction (`redaction/SecretRedactor`)

High-confidence patterns (private keys, AWS key IDs, GitHub tokens, Google API
keys, connection-string passwords) already apply to every file. The key-name
rule currently covers only `.properties`, `.yml/.yaml`, `.json` and `.env`.

1. **Dockerfile `ENV` / `ARG` lines.** `ENV DB_PASSWORD=hunter2` and
   `ENV DB_PASSWORD hunter2` (space-separated form) are not redacted. Add a
   Dockerfile-specific rule for `ENV`/`ARG` with sensitive key names.
2. **XML elements.** `<password>…</password>`, `<secret>`, `<token>`,
   `<apiKey>` and similar (e.g. Maven `settings.xml`, Spring XML config) are not
   redacted. Add an element-name rule for `.xml` files; keep it linear (no
   nested-element regex) and leave `${…}` placeholders alone.
3. **`.toml`, `.ini`, `.conf` files.** Extend the configuration-file rule to these
   formats (`key = "value"`, `key=value`, `[section]` headers), with tests for
   each.
4. **`package.json` dependency versions.** Keys inside `dependencies`,
   `devDependencies`, `peerDependencies` and `optionalDependencies` are package
   names, not secrets; `"jsonwebtoken": "^9.0.0"` currently has its version
   redacted because the key contains "token". Exempt these sections before any
   npm dependency parsing is added.

## GitHub access

5. **Shared server token on the connection path.** Connection-based analysis
   (`repositoryConnectionId`) still calls GitHub with the server-wide
   `GITHUB_TOKEN`, so access is limited only by DevBuddy's ownership checks,
   not by the user's own GitHub permissions. Resolve this when GitHub
   connections are implemented: use the user's own token
   (`github_connections.token_reference`, stored outside the database) for
   every call on that path, and never fall back to the server token for
   private repositories.
6. **Private-repository existence oracle.** One-off URL analysis returns 403
   for a private repository that the server token can see, but 404 for a
   repository that does not exist, so a caller can probe which private
   repositories exist. Handle later, e.g. return the same response for both
   cases or stop using the server token on the one-off path.
   `POST /api/runs` (Build 1) reuses the same public-repository check and
   therefore has the same oracle.

## Error responses

7. **400 responses echo `IllegalArgumentException` messages.**
   `GlobalExceptionHandler` returns the exception message as the error text so
   that our own validation messages (e.g. "Invalid GitHub repository URL")
   reach the client. An `IllegalArgumentException` thrown by a library would
   be echoed the same way and could expose internal details. Replace with a
   dedicated validation exception for our own messages and a generic message
   for everything else.

## Runs (Build 1)

8. **GitHub rate limiting.** `POST /api/runs` makes a synchronous GitHub
   metadata call with the shared server token for every request. One user (or
   a script) can exhaust the server-wide GitHub quota and break analysis for
   everyone. Add a per-user rate limit on Run creation and on one-off analysis,
   handle GitHub's `X-RateLimit-Remaining` / 429 / secondary-limit responses
   with backoff, and surface "try again later" without leaking quota details.
   The per-user cap on unfinished Runs (default 3) limits concurrency, not
   request rate.

## Authentication

9. **JWT remains valid after the user is deleted.** Tokens are only checked
   for signature and expiry; nothing checks that the user still exists, and
   there is no revocation. Build 1 handles it per endpoint: `POST /api/runs`
   returns 401 (the cap lock finds no `users` row) and Run lookups return 404,
   with a test proving no 500. Fix centrally: check the user (or a
   token-version / `tokens_valid_after` column) in `JwtAuthenticationFilter`,
   and add revocation on password change and account deletion.

## Build 2 notes (real runner)

10. **[DONE in Build 2A]** **A runner must only claim its own user's Runs.** Build 1's claim query
    (`RunRepository.lockNextClaimableId`) takes the oldest claimable Run of
    *any* user, which is fine for the in-process fake worker only. A real
    runner (user's machine or sandbox) must authenticate with a runner token
    tied to one user, and claim, heartbeat and advance only Runs whose
    `owner_user_id` matches that user; `runner_id` must come from the
    server-side token, never from the request body. Needs an
    `owner_user_id` filter (and index) on the claim query and tests that
    runner A cannot claim, heartbeat or advance user B's Run.

## Runner keys (Build 2A)

11. **Key expiry and cleanup.** `runner_keys.expires_at` exists and is
    enforced, but nothing sets it: `POST /api/runner-keys` takes only a name
    (no `expiresInDays` in 2A). Revoked and expired keys stay listed forever
    and there is no delete. Add an optional expiry on creation (and perhaps a
    default maximum lifetime), a delete or cleanup for keys revoked or expired
    longer than N days, and a note in the list response when a key is close to
    expiry.
12. **Throttle failed runner-key authentications at deployment.** Every
    well-formed `Bearer dbr_...` triggers one indexed lookup by hash.
    Guessing a 256-bit key is infeasible, but a flood of well-formed fake keys
    still costs one database query each. Malformed keys are already refused
    before any lookup. At deployment, add per-IP rate limiting of 401s on
    `/api/runner/**` (reverse proxy or a filter) and alerting on spikes.
13. **Fail a revoked key's active Runs immediately.** Revoking a key blocks
    its next request at once, but a Run it holds (ANALYZING, later TESTING /
    INVESTIGATING) is only failed by the reaper once the heartbeat goes stale
    (`devbuddy.runner.heartbeat-timeout-ms`, 30 s). On revoke, fail every
    active Run whose `runner_id` starts with `<keyId>:` through
    `RunStateMachine.transition` with reason "runner key revoked", in the
    revoke transaction.

## Runner keys (Build 2A)

11. **Runner key expiry and cleanup.** `runner_keys.expires_at` is enforced
    (an expired key gets 401) but nothing sets it: `POST /api/runner-keys` takes
    only a name, by decision for 2A. There is no cleanup job either, so revoked
    and expired keys stay in the table and in `GET /api/runner-keys` forever.
    Add an optional expiry on creation (and perhaps a default maximum
    lifetime), and a scheduled cleanup (or delete endpoint) for keys revoked or
    expired longer than N days.
12. **Throttle failed runner-key authentication at deployment.** Malformed
    keys are refused before any database lookup, but every well-formed
    `Bearer dbr_...` costs one indexed lookup by hash. Guessing a 256-bit key
    is infeasible, yet a flood of well-formed fake keys still loads the
    database. At deployment, rate-limit 401s on `/api/runner/**` per client IP
    (reverse proxy or filter) and alert on spikes.
13. **Fail a revoked key's active Runs immediately.** Revoking a key blocks
    its next request at once, but an active Run it holds is only failed by the
    reaper once the heartbeat goes stale (`devbuddy.runner.heartbeat-timeout-ms`,
    30 s). On revoke, fail every active Run whose `runner_id` starts with
    `<keyId>:` through `RunStateMachine.transition` with reason
    "runner key revoked", in the revoke transaction, instead of waiting for
    the reaper.
14. **Lease token per claim (Build 2B).** The instance id is chosen by the
    runner and is not a credential, so anything holding the key can act as
    any instance, and a restarted process that reuses an instance id can keep
    driving the old Run. Claim should return a random lease once, store only
    its hash on the Run (`runs.lease_hash`, new migration) and require it on
    every later per-Run call. With the lease, the instance header becomes a
    label for logs.
15. **Retire server-side repository fetching (local-first model).** In the
    runner flow the server never fetches the repository: `repoUrl` in the
    claim response is an identifier only. Retire `POST /api/repository/analyze`
    and server-side repository snapshots. Items 5 (shared server token), 6
    (private-repository existence oracle) and 8 (GitHub rate limiting) mostly
    go away with them.
16. **Approved-plan integrity (Build 2B).** The runner must execute exactly the
    plan the user approved, i.e. the server's stored copy (fetched at TESTING
    claim), never its own local version. Plans whose commands were redacted
    (`redactedFields` lists `recipe.buildCommand` / `recipe.startCommand`)
    cannot run as stored: before TESTING the secret must move to an
    environment variable (named in `envVarNames`) and the plan be re-reported.
