---
title: Instance administration
description: "The instance-wide console: what it configures and how it differs from workspace administration."
---

# Instance Admin Area

The **instance admin** (also called super-admin) area lets an operator manage the whole Hephaestus deployment.
A **workspace admin** has powers only within one workspace.

## Who is an instance admin?

- An account with `Account.appRole == APP_ADMIN` (ADR 0017 native auth).
- The issuer mints the namespaced **`app_admin`** granted authority for these accounts (`JwtPrincipalFactory`).
  This differs from the per-workspace `admin` role, which comes from membership and never appears in the JWT.
  `SecurityUtils.isSuperAdmin()` reads `app_admin`.
  `WorkspaceContextFilter` grants an instance admin `WorkspaceRole.ADMIN` in **any active workspace, with or without membership**.
  It grants `ADMIN`, never `OWNER`, because members grant ownership.
  It records that access: see [Elevated workspace access](#elevated-workspace-access).
- The authority comes **only** from `appRole`.
  `JwtPrincipalFactory` removes reserved authorities (`app_admin`/`admin`) that arrive through grantable `account_feature` rows.
  Thus, an `/admin/users`-granted flag can never confer instance-admin access.
- First-admin bootstrap (no DB seed required) is covered separately in the
  [operator bootstrap instructions](/admin/instance-admin#bootstrap-and-recovery).

## The shell

The [operator guide](/admin/instance-admin) owns console tasks and the user-view procedure.
This page owns authorization, routing, and persistence implementation.

The admin area is a **dedicated sidebar context** (`AppSidebar` `context === "admin"`).
It has its own "Back to app" header, with no workspace switcher, as in the GitLab/Grafana "admin area" pattern.
It does **not** reuse the mentor context.
The always-present sidebar footer has an `app_admin`-gated **"Instance admin"** entry.
Thus, a newly bootstrapped admin with **zero workspaces** can reach it.

`beforeLoad` (`isAppAdmin`) guards the `/admin` route tree.
The server enforces `@PreAuthorize("hasAuthority('app_admin')")` on every endpoint below.
The client is not a security boundary.

## Endpoints

Instance metadata endpoints under `/admin`, all gated by `hasAuthority('app_admin')`:

| Endpoint | Purpose |
| --- | --- |
| `GET /admin/users` (`adminListUsers`) | Paged account list |
| `PATCH /admin/users/{id}` (`adminUpdateUser`) | Change an account's app role (last-admin guard. Cannot self-demote) |
| `DELETE /admin/users/{id}/sessions` (`adminRevokeUserSessions`) | **Force sign out**: revoke all of an account's active sessions. Audited as `JWT_REVOKED`. |
| `GET /admin/workspaces` (`adminListWorkspaces`) | **Metadata-only** overview of every workspace (slug, status, provider, owner login, member count, created-at). Cross-tenant through `@WorkspaceAgnostic`. This endpoint itself returns **no tenant content**. Private user content is reached through [read-only user views](#read-only-user-views). |
| `GET /admin/audit` (`adminListAuthEvents`) | Read-only viewer over the append-only `auth_event` log (logins, user views, role changes, deletions). Paged, newest-first, filterable by event type. [read-only user views](#read-only-user-views) says what a `USER_VIEW` row carries. |
| `GET /admin/config-audit` (`adminListConfigAuditEvents`) | Read-only viewer over `config_audit_event` — who changed which workspace setting, when, and from what to what. Rows are immutable inside the retention window (DB trigger). `ConfigAuditRetentionJob` is the only way one leaves. |
| `/admin/llm/connections*` (`adminListLlmConnections`, `adminCreateLlmConnection`, `adminGetLlmConnection`, `adminUpdateLlmConnection`, `adminDeleteLlmConnection`, `adminProbeLlmConnection`, `adminProbeLlmConnectionDraft`) | The instance LLM connection catalog. Routing identity (base URL, wire API, auth mode) is immutable after create. Probe tests a saved or draft connection before anything is enabled. |
| `/admin/llm/models*` (`adminListLlmModels`, `adminCreateLlmModel`, `adminGetLlmModel`, `adminUpdateLlmModel`, `adminDeleteLlmModel`, `adminUpdateLlmModelPrice`, `adminUpdateLlmModelSharing`) | Models under a connection, their prices (temporal supersede-on-insert into `llm_model_price`), and who may use them — public, or granted per workspace. |
| `GET`/`PUT /admin/llm/settings` (`adminGetLlmSettings`, `adminUpdateLlmSettings`) | The instance LLM settings singleton: egress host allowlist and `allowWorkspaceConnections`, the switch that lets workspaces register their own provider connections. |
| `GET /admin/llm/usage` (`adminGetLlmUsageReport`) | Cross-workspace monthly LLM usage and budget report, split by purse (shared models vs each workspace's own provider). |
| `PUT /admin/workspaces/{workspaceSlug}/llm/budget` (`adminUpdateWorkspaceLlmBudget`) | Set **or clear** a workspace's monthly cap on **shared-model** spend — clearing is `PUT` with `monthlyBudgetUsd: null`, not `DELETE` (there is no `DELETE` mapping. It returns 405). The workspace's cap on its own provider is a different endpoint under `/workspaces/**`, set by the workspace's own admin. |
| `GET /admin/settings` / `PATCH /admin/settings/silent-mode` | Read or change the instance-wide outbound delivery brake. Releasing requires `If-Match` with the ETag returned by `GET`, so a stale browser cannot release a newer incident response. |

## Instance silent mode

[Instance admin](/admin/instance-admin#silent-mode) owns operator behavior and recovery.
`GET /admin/settings` returns an ETag. `PATCH /admin/settings/silent-mode` requires `If-Match`
when releasing the brake. Provider gateways enforce it independently from observation persistence.

## Recent sign-in gate

Instance-admin actions that change access require a completed sign-in younger than `hephaestus.auth.step-up-max-age` (`HEPHAESTUS_AUTH_STEP_UP_MAX_AGE`, default 5m).
These actions include app-role changes, force sign-out, user views, login-provider mutations, and LLM connection registration or removal.
The same requirement applies to identity attachment for every user.
A new link is a permanent second way into the account.

The completed OAuth login stamps the standard `auth_time` claim once.
Every rotation copies it unchanged.
Thus, silent keep-alive cannot make a session appear newly signed in.
`SecurityConfig` converts it to Spring Security's `FactorGrantedAuthority` for the authorization-code factor.
[`AllRequiredFactorsAuthorizationManager`](https://docs.spring.io/spring-security/reference/servlet/authentication/mfa.html) compares it with the window.

The comparison accepts anything not yet expired.
Thus, a session stamped by a pod whose clock leads the enforcing pod's clock remains fresh.
Local clock skew must never tell a newly signed-in administrator to sign in again.

Declare the requirement with `@RequiresRecentSignIn` on the handler.
To decline it, use `@RecentSignInExempt(reason = …)` on the handler or controller.
`RecentSignInByDefaultArchTest` fails the build when an instance-admin mutation carries neither annotation.
Thus, a new administrative action cannot ship without a recorded decision.
`AuditByDefaultArchTest` gives the audit trail the same structure.

The refusal uses the `auth_event` type that the handler already declares for `@Audited`.
It records a `FAILURE` that names the account whose session failed the check.
It also increments `auth.step_up.denied{action}`.

### What it does and does not stop

It limits a **hijacked admin session**.
A stolen cookie is dangerous only within the window, and the audit trail records every attempt.
It does not stop an attacker who can control the browser through XSS, a compromised machine, or a session beside the operator's.
That attacker can complete confirmation too.

It is **not a second factor**.
An existing GitHub or GitLab session can satisfy OAuth without a new authenticator challenge.
Protected admin access also requires local passkey verification.
See [Admin passkeys](/admin/passkeys) for the policy and recovery procedure.
It does not stop a malicious administrator authorized for the action.
The audit trail serves that purpose.

A refused action is never replayed after confirmation.
The SPA reopens the action for the operator to review and submit again.
A queued privileged write is exactly what an attacker would want confirmation to unlock.

## Read-only user views

**Instance admin → Workspaces → View users → View as user** opens the normal workspace app as a
human workspace member, including one without a Hephaestus account. The instance administrator
remains authenticated as themselves. The viewed person is the synced SCM user behind a workspace
membership ([auth glossary](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/auth-glossary.md)).
**Linked account** reads `identity_link.external_actor_id`.

The browser keeps the selected workspace, user ID, and reason in the tab's session storage.
When a view starts or exits, the app reloads so cached results cannot cross identities.
When it loads the current account, it drops a stored view opened by another account.
`applyUserViewHeaders` adds `X-User-View-Workspace`, `X-User-View-User`, and the reason header to every request.
It excludes the administrator's own sign-in, account, consent, feature-flag, and identity reads.

During a view, the administrator's feature flags do not apply.
A banner names the viewed user and offers an exit.
The account menu still names the administrator, whom it signs out.

The selection endpoints use the `User view` tag in `server/openapi.yaml`.
All are `GET` under `/workspaces/{slug}/user-view/users`.
Like every instance-administrator controller, all require `@PreAuthorize("hasAuthority('app_admin')")`.
Each request checks token authority and session revocation.
Demotion and deletion revoke sessions.

Every selection handler addressed at a `{userId}` carries `@UserViewRead` (`@RequiresRecentSignIn` + `@Audited(AUTH_EVENT, "USER_VIEW")`).
`UserViewArchitectureTest` fails the build for a `/user-view` handler that is not a `GET` or names `{userId}` without this annotation.
`UserViewAuthorizationConfig` advises the annotation after the [recent sign-in gate](#recent-sign-in-gate).
Thus, a refused confirmation leaves no successful `USER_VIEW` record.

Before the handler runs, it requires the `X-User-View-Reason` header, encoded as percent-encoded UTF-8.
`UserViewAccessService` accepts 1–500 decoded characters, excluding control and format characters.
It resolves the viewed member through `ViewedUserService` and commits the row.
If the row does not commit, it answers 503.
`OpenAPIConfiguration.userViewReasonHeader` declares the header on every such operation, so the generated client requires it.

Before every other rule, `SecurityConfig` admits a request with a view header only as an instance administrator's `GET`.
`UserViewSessionFilter` answers 403 for a route outside `READ_PATHS`.
It answers 404 for a workspace in the path other than the viewed one.
Both checks occur before any audit row.
A teammate's profile remains readable as it is for the member, and its row records that read.

Every request requires a [recent sign-in](#recent-sign-in-gate).
It resolves the human member and commits the `USER_VIEW` row before the handler runs.
If the row does not commit, it answers 503.
`WorkspaceContextFilter` gives the request the member's workspace roles and SCM actor ID, never instance-admin elevation.
The in-app feedback read does not mark feedback delivered during a view.

When the sign-in window expires, the SPA opens the access confirmation.
A new sign-in returns to the same page with the view intact.

This is a view of existing content, not a user login. It does not enter onboarding, choose personal
settings or create an account, so it cannot reproduce the member's first-login experience.

A successful `USER_VIEW` row records authorization to attempt a read, not proof that the handler returned content.
A missing observation or conversation can still produce a 404 after the row commits.
The row carries these fields:

- `acting_account_id` = the instance administrator.
- `account_id` = the viewed user's linked account or null.
- `viewed_user_id` = the viewed user.
- `workspace_id`.
- `details` = `{"reason": …, "read": "<request path?query>"}`.

`AccountPurger` nulls `ip_inet`, `user_agent`, and `details` when the erased account is either recorded account or the account behind `viewed_user_id`.
Thus, it runs before deletion of that account's identity links.
The read budget is a [setting](/admin/configuration-readiness#session-deadlines).
[ADR 0017](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0017-replace-keycloak-with-spring-native-auth.md#update--2026-09-11) explains why this does not use Spring Security's `SwitchUserFilter`.

## Elevated workspace access

An instance admin who is not a member of a workspace still reaches it as a workspace admin, and both
audit trails say so.

`WorkspaceContextFilter` makes this decision once per request.
For a non-member, it records the workspace in `WorkspaceElevationContext`, a request-local `ThreadLocal`.
The same `finally` clears it and the workspace context.
It is deliberately not inheritable, so a background executor task starts unelevated.
Downstream code reads the flag from this context, not an argument.

`ConfigAuditActor` explains the actor-attribution reason: a producer can neither forget the flag nor assert one it did not earn.

- `auth_event` gains a **`WORKSPACE_ELEVATION`** row.
  It marks an access *window*, not a request.
  `WorkspaceElevationAuditAdapter` de-duplicates per `(account, workspace)` for 15 minutes in a bounded per-process cache.
  Thus, workspace reads do not obscure the user-view and role-change events that the viewer exists to show.
  The adapter claims the cache only after it writes the row.
  Eviction or a second replica can add a duplicate marker.
  An extra window marker is harmless, but a lost marker is not.
- `config_audit_event` gains **`elevated_via_instance_admin`** per row, resolved for that row's own `workspaceId`.
  Thus, an instance-scoped change with no workspace never gets the wrong tag.
  The adapter does not de-duplicate configuration changes.
  Each carries its own bit.

Both admin consoles surface it, and `GET /admin/audit/export` carries it alongside the viewed-user identifier in CSV exports.

The flag does not replace the viewed-user identifier: elevated workspace administration and a
private user view are different permissions. `false` means "no elevation recorded", not "the actor
was a member": rows written before the flag existed all read `false`.

## Deferred / follow-up

- **`APP_AUDITOR`** read-only tier: not built. A single-operator instance has no second audience for it.
  The enum and authority design permit its addition.
LLM governance is not on this list — it is built. An instance admin registers connections and models
under `/admin/llm/*`, prices them, and grants them to workspaces. A workspace may add its own
connection when instance settings permit it. Usage is metered into `llm_usage_event`.
Two independent monthly budgets cap it: the instance cap on shared-model spend and the workspace cap on its own provider.
The budgets are never summed.
[ADR 0026](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0026-per-purpose-agent-bindings-and-llm-governance.md)
records the decision.
