# ADR 0019: Workspace membership is keyed on `Account`, not the SCM `User`

**Status:** Proposed
**Date:** 2026-06-09
**Authors:** Hephaestus maintainers
**Finishes:** [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md) (the `workspace_membership.user_id → account_id` rename it specifies but never implemented)
**Builds on:** [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md), [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md)

> **Scope note.** This ADR is intentionally carved out of the practice-review-config / dev-login
> work (branch `1011-practice-review-mentor-config`). That branch ships the **dev/test login** and
> the **instance super-admin workspace elevation** — both forward-compatible with this change — but
> NOT the membership re-key, which is an irreversible, suite-untested PK migration and must land as its
> own PR with this ADR.

## Context

ADR 0017 established `Account` as the Hephaestus-native principal and documents (auth-glossary §13,
ADR 0017 §Data-model split) that `workspace_membership.user_id` should be **renamed to `account_id`**.
The code never finished it: `workspace.WorkspaceMembership` is still `@EmbeddedId (workspace_id, user_id)`
with a `@ManyToOne` to the **SCM** `integration.scm.domain.user.User` (NOT NULL FK). Consequences:

- **A workspace cannot have a member without a GitHub/GitLab identity.** This blocks SCM-less workspaces
  and forces any non-OAuth principal (a dev/test account, a future SERVICE principal — Issue #1324) to
  synthesise a fake SCM `User` just to hold a role.
- **`WorkspaceContextFilter` couples authorization to SCM identity** — it resolves the account → SCM
  users (`CurrentAccountUsers`, cross-provider union) → membership. The "auto-heal first identity as
  ADMIN on a zero-membership workspace" hack exists only to paper over the seeding gap, and is a latent
  privilege-escalation footgun.
- The cross-module SPI `core.auth.spi.AccountWorkspaceMembershipQuery` must launder every query through
  `login → User → membership` and its Javadoc apologises for it. When the contract apologises, the model
  is wrong.

This is the canonical multi-tenant inversion (WorkOS/Auth0/Logto/Azure): **authentication identity is
global; membership joins that identity to a tenant; roles live on the membership.** SCM logins are just
linked credentials (`IdentityLink`). Keying membership on a *credential* (`User` = one SCM login) rather
than the *identity* (`Account`) is the defect.

## Decision

Re-key `workspace_membership` to **`(workspace_id, account_id)`** (FK → `account.id`). The SCM `User`
(to be renamed `ExternalActor` per ADR 0017) stays an **attribution projection** — PR authorship, review
attribution, team membership, leaderboard points — which are genuinely SCM-actor facts and must NOT move
to the account.

Split the two natural keys the membership row currently conflates:

- **Authorization fact** (`role`) → keyed on `account_id`.
- **SCM-attribution facts** (`league_points`, `hidden`) → keyed on the SCM actor. v1 may keep them on the
  membership row behind a **nullable `external_actor_id`** (account-only members have it `NULL` and never
  appear on the leaderboard); a later step may extract a `workspace_contributor_stats(workspace_id,
  user_id, …)` table. The non-negotiable part is the **PK = `account_id`**.

`workspace → core.auth` is a permitted Modulith dependency (the forbidden direction is `core.auth →
integration`), so `WorkspaceMembership → Account` is legal and the change *removes* the SPI laundering.

## Migration shape (detail belongs in the implementing PR)

The suite runs `ddl-auto:create`, so the changelog is **untested by CI** — a PK swap that passes every
test can still brick prod boot. Use **expand/contract**: add nullable `account_id` (+ `external_actor_id`)
→ backfill from the `user_id → identity_link → account_id` graph → **reconcile orphan members with no
`IdentityLink`** (PAT bots, the synthetic `admin` user — the correctness cliff: one unmapped row fails
prod boot) → flip NOT NULL + swap the PK. Gate it with a **Testcontainers `liquibase:update` test** (the
de-risking the suite lacks today). The implementing PR owns the SQL and the per-orphan drop-vs-provision
policy.

Authorization moves to `account_id`; SCM-actor facts (leaderboard points, team membership, PR/activity
attribution) stay keyed on the SCM actor. An account with no SCM identity is a valid member with no
attribution surface — the SCM-less semantics, by *absence*, not synthesis. `WorkspaceContextFilter`'s
auto-heal hack is deleted (the founder's account is seeded directly); the super-admin elevation from the
dev-login PR stays.

## Consequences

- **Enables** SCM-less workspaces, a first-class dev/test login, and the SERVICE principal (#1324) — all
  without fake SCM rows.
- **Closes** the auto-heal privilege footgun and the cross-provider-union escalation surface; the
  super-admin elevation (this PR) and its audit tagging (#1323) compose cleanly.
- **Risk** concentrated in the migration's orphan reconciliation; gated by the new `liquibase:update` test.

## Sources

WorkOS/Auth0/Logto/Clerk multi-tenant guides; GitHub org-roles & GitLab admin/members docs (instance
admins reach any group without membership — the model the super-admin elevation adopts). See the design
review in the originating branch.

## Update — 2026-09-17

Not implemented; [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md) § Update 2026-09-17
has the schema, including the name the `ExternalActor` rename § Decision anticipates. Two names in
§ Context:

- The auto-heal is `workspace.context.WorkspaceMembershipAutoSeeder`, called from
  `WorkspaceContextFilter` and gated by `hephaestus.workspace.auto-seed-membership`, which
  `application.yml` sets to `true` and `application-prod.yml` to `false`.
- The SPI is `core.auth.spi.AccountWorkspaceMembershipQuery`, implemented by
  `workspace.AccountWorkspaceMembershipQueryAdapter`.
