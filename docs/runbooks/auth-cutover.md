# Runbook: Keycloak → Spring-native auth cutover

Operational guide for shipping and operating the auth replacement (ADR 0017). Read
[`auth-architecture.md`](../auth-architecture.md) first.

## Pre-launch checklist

- [ ] `HEPHAESTUS_AUTH_ISSUER` set to the public origin (`https://hephaestus.build`).
- [ ] `HEPHAESTUS_AUTH_STATE_COOKIE_KEY` set to a base64-encoded 32-byte value (NOT blank —
      blank falls back to an ephemeral per-boot key and abandons in-flight logins on restart).
- [ ] `hephaestus.security.encryption-key` set to a 32-character value (already required for
      credential encryption at rest). The JWT signing private key is sealed at rest with it
      (AES-256-GCM); **prod refuses to boot if an active signing key is unsealed** — so this
      must be set before first boot and kept stable, or all sessions invalidate when it changes.
- [ ] `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET` registered (GitHub OAuth App,
      callback `https://<host>/api/login/oauth2/code/github`) (the `/api` prefix is stripped by Traefik before Spring sees it).
- [ ] `GITLAB_OAUTH_CLIENT_ID` / `GITLAB_OAUTH_CLIENT_SECRET` registered (GitLab OAuth application,
      callback `https://<host>/api/login/oauth2/code/gitlab`, scope `read_user`).
      Set `GITLAB_OAUTH_BASE_URL` to the instance (defaults to `https://gitlab.com`; e.g.
      `https://gitlab.lrz.de` for a self-hosted instance) and optionally `GITLAB_OAUTH_DISPLAY_NAME`.
- [ ] Reverse proxy (Coolify / TUM) verified to NOT inject a `Domain=` on Set-Cookie — a
      `__Host-` cookie with a Domain attribute is silently dropped by browsers → infinite
      redirect loop. Smoke-test the full login on staging behind the real proxy.
- [ ] Postgres image ships `pg_partman` (`ghcr.io/hephaestus-build/postgres` — see ADR 0018)
      plus `citext`. `auth_event` is partman-managed (create-ahead + 12-month retention); maintenance
      runs via `AuthEventPartitionMaintenance` (`CALL run_maintenance_proc`, ShedLock) — no
      `shared_preload_libraries`. Liquibase's `create_parent` builds current+2 partitions + the
      default at migration time, so the first insert is safe immediately.
- [ ] `jwt_signing_key` seeded (auto-seeds a sealed key on first boot via
      `AuthJwtConfig.seedKeysOnStartup`; requires `hephaestus.security.encryption-key`, see above).
- [ ] TLS 1.3 + HSTS preload confirmed at the edge.
- [ ] First instance admin designated — see below (the super-admin UI is admin-gated, so the first
      `APP_ADMIN` cannot be minted from the UI).

## First instance admin (bootstrap)

Instance super-admin (`APP_ADMIN`) is **separate** from per-workspace roles (those derive from
membership). It is an operator concern — "who runs this instance" — so the source of truth is
operator config, not any workspace's SCM team. Two mechanisms, no data backfill:

1. **Allowlist (preferred):** `hephaestus.auth.bootstrap-admins` — a list of `<registrationId>:<who>`.
   `who` is either **`@username`** (recommended, readable) or the stable numeric **`subject`**. A listed
   identity is promoted to `APP_ADMIN` on login (idempotent, promote-only — it never demotes; demotion
   stays a `/admin/users` action), and it works on a cold instance. As env (comma-separated):
   `HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS=gitlab:@m.mustermann,github:@octocat` (the GitLab registration
   id is `gitlab` regardless of which instance it federates to).
   - On an **institutional GitLab** (e.g. gitlab.lrz.de) where usernames are bound to a managed
     identity and not recycled, `@username` is safe. On **public github.com**, prefer the numeric `subject`
     (`github:1234567`, from `https://api.github.com/users/<login>`) because a renamed/relinquished
     GitHub handle can be reclaimed by someone else. Matching is on the authenticated identity, never
     on email, so it is not nOAuth-vulnerable either way.
   - If a designated admin logs in and lands as a plain user, the server logs (INFO) the exact
     `provider subject username` it saw on that first login — copy it into the allowlist.
2. **Break-glass token (fallback / lockout insurance):** set `hephaestus.auth.bootstrap-token`
   (`HEPHAESTUS_AUTH_BOOTSTRAP_TOKEN`) to a high-entropy secret, then have the operator log in and
   `POST /auth/bootstrap-admin` with `{"token":"…"}`. It promotes the authenticated caller **only
   while no admin exists** (atomic, self-disabling) and 404s when the token is unset. Deliver the
   token out-of-band; it is never logged. Unset it after bootstrapping.

Prefer the allowlist; keep the token as the safety net for "I can't predict the identity" or a
zero-admin lockout. Once one admin exists, everyone else is managed in `/admin/users`.

## Rollback

The cutover commit (the one that flips the resource-server decoder from Keycloak to
`RevocationAwareJwtDecoder` and deletes Keycloak) is intentionally a single revertable
commit. For 30 days post-launch:

1. `git revert <cutover-sha>` restores the Keycloak resource-server chain.
2. Re-enable Keycloak: `git revert <cutover-sha>` (step 1) restores the inline Keycloak service
   blocks in the compose files and the `KEYCLOAK_*` env vars.
3. Existing `account` / `identity_link` rows are harmless to leave in place — the reverted
   code simply ignores them.

Data note: the migration is purely **additive** — it creates the new auth tables (`account`,
`identity_link`, `account_feature`, `jwt_signing_key`, `issued_jwt`, `auth_event` (+ partitions),
`auth_rate_limit_bucket`, `account_export`) and drops the now-unused `user.keycloak_subject`
column. The `"user"` table is otherwise left in place (still mapped as `User`); its rename to
`ExternalActor` and the activity-FK rewrite are deferred to a planned Liquibase squash. No existing
activity/membership columns are renamed or dropped, so leaving `account` / `identity_link` rows in
place on a revert is harmless.

## Existing users at cutover (no backfill required)

Existing users are **not** pre-migrated into `account` / `identity_link` — those tables start
empty, and there is no backfill changeset. On a user's first post-cutover login an `Account` +
`IdentityLink` are JIT-created. This is safe because **workspace authorization never keys on
`account_id`**: the issued JWT carries `preferred_username` = the git login, and
`WorkspaceContextFilter` resolves the current `User` by login (case-insensitive) → the existing
`workspace_membership` rows (still keyed on `user_id`). Memberships, roles, leaderboard points and
activity history therefore carry over automatically.

- ⚠️ **Risk — username drift.** The bridge is git-login string equality. GitHub / GitLab
  usernames are mutable; if a user renamed since the last sync, the stale `User.login` won't match
  their fresh `preferred_username` and they will appear as a non-member (403) until a sync updates
  the row. **Mitigation: run a fresh user sync immediately before the cutover** so `User.login`
  reflects current provider usernames. **Verify on staging** with at least one user whose provider
  username differs from the value currently in the `"user"` table. (The FK-stable fallback keyed on
  the numeric provider id lives in `AuthenticatedGitProviderUserService` but is not on the
  `WorkspaceContextFilter` hot path, so the sync is the operational safeguard.)

### Migration smoke-tested

The Liquibase migration was validated against Postgres 16 in both directions: a **fresh** apply
(`db/master.xml`, `prod` context — all auth tables + the `auth_event` RANGE partitions seeded) and an
**upgrade** from the pre-cutover `main` schema. The cutover adds exactly **one** changelog —
`1780825201546_changelog.xml` (the consolidated auth changelog) — and reuses the integration changelog
(`1780313973588`) unchanged, so the upgrade applies cleanly with no duplicate `DATABASECHANGELOG` rows.

## JWK rotation

- **Rotation is not implemented.** There is no `rotate()` method, no admin endpoint, and no
  scheduled task; one active key signs every token, and re-keying means deploying with a fresh key
  while the old row keeps verifying until it is retired by hand. The only supported recovery is the
  "lost key" path below (clear `jwt_signing_key`, restart, everyone re-authenticates). Treat
  zero-downtime rotation as an open item, not an operational procedure.
- Each pod reloads its JWK cache from the DB at most once per minute, so after a rotation other
  pods pick up the new key within that TTL (a token signed by the still-valid previous key keeps
  verifying meanwhile). A NATS `auth.signing-key.rotated` push to make the cross-pod refresh
  immediate (calling `invalidateCache()`) is a tracked follow-up, not yet wired.
- **Lost key disaster:** if `jwt_signing_key` is lost/corrupted, all sessions are invalid.
  Recovery: clear the table, restart (auto-seeds a fresh key), all users re-login. No data
  loss beyond active sessions.

## Revocation

- Logout / "sign out everywhere" / admin-revoke / impersonation-exit set `issued_jwt.revoked_at`.
  Because the decoder re-checks `issued_jwt(jti)` on every request (the cache is negative — REVOKED
  verdicts only), the revocation is effective on every pod within DB visibility lag, not the cache
  TTL. A compromised account is killed cluster-wide at once by suspending it (the per-request
  account-status gate, independent of this cache). Cross-pod sub-second propagation via a NATS
  `auth.jwt.revoked` push is a tracked follow-up.
- The `issued_jwt` table is swept of expired rows by a scheduled job; alert if its row count
  grows > 2× baseline (sweep broken).

## Impersonation

- Every `IMPERSONATION_BEGIN` is audited with `(account_id=target, acting_account_id=operator)`
  and a mandatory reason. Alert on every begin during normal hours (not just failures).
- Impersonation sessions are read-only unless the operator sends
  `X-Impersonation-Allow-Writes: true` after a second confirmation.

## Key SLOs to dashboard

- login success rate (`auth.login{result=success}` vs `failure`) > 99% (5-min window) → page
- `auth.ratelimit.blocked` rate spike → notify (credential-stuffing / misconfigured client)
- `auth.token.refresh` p99 latency within budget → notify (DB / signing-key contention)
- JWK rotation success = 100%

## Known follow-ups (not blocking launch)

Shipped with the cutover: Bucket4j rate limits, the 48-hour soft-delete + hard-delete sweep, security
headers (CSP report-only), at-rest sealing of the JWT signing key, and the per-request
negative-cache revocation check. Still tracked as named follow-up issues in ADR 0017: cross-pod NATS propagation for
revocation and JWK rotation, flipping CSP from report-only to enforce, the `JwtClaimsLogScrubber`
Logback filter, and full OpenTelemetry tracing.
