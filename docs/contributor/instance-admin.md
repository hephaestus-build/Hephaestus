---
title: Instance administration
description: "The instance-wide console: what it configures and how it differs from workspace administration."
---

# Instance Admin Area

The **instance admin** (a.k.a. super-admin) area lets an operator manage the whole Hephaestus
deployment — distinct from a **workspace admin**, whose powers are scoped to a single workspace.

## Who is an instance admin?

- An account with `Account.appRole == APP_ADMIN` (ADR 0017 native auth).
- The issuer mints the namespaced **`app_admin`** granted authority for such accounts
  (`JwtPrincipalFactory`). This is deliberately distinct from the per-workspace `admin` role, which
  is membership-derived and never appears in the JWT. `SecurityUtils.isSuperAdmin()` reads
  `app_admin`, and `WorkspaceContextFilter` grants an instance admin `WorkspaceRole.ADMIN` in **any
  active workspace, membership or not** — deliberately `ADMIN` and never `OWNER`, because ownership
  is a member-granted role. That access is recorded: see [Elevated workspace access](#elevated-workspace-access).
- The authority comes **only** from `appRole` — `JwtPrincipalFactory` strips any reserved authority
  (`app_admin`/`admin`) that might arrive via a grantable `account_feature` row, so an
  `/admin/users`-granted flag can never escalate to instance admin.
- First-admin bootstrap (no DB seed required) is covered separately in the
  [auth-cutover runbook](https://github.com/ls1intum/Hephaestus/blob/main/docs/runbooks/auth-cutover.md#first-instance-admin-bootstrap).

## The shell

The admin area is a **dedicated sidebar context** (`AppSidebar` `context === "admin"`) — its own
"Back to app" header with the workspace switcher suppressed (the GitLab/Grafana "admin area" pattern),
**not** a reuse of the mentor context. It is reachable from an `app_admin`-gated **"Instance admin"**
entry in the always-present sidebar footer, so a freshly bootstrapped admin with **zero workspaces**
can still reach it. The `/admin` route tree is guarded in `beforeLoad` (`isAppAdmin`), and every
endpoint below is enforced server-side by `@PreAuthorize("hasAuthority('app_admin')")` — the client
is not a security boundary.

## Endpoints

All under `/admin`, all gated by `hasAuthority('app_admin')`:

| Endpoint | Purpose |
| --- | --- |
| `GET /admin/users` (`adminListUsers`) | Paged account list |
| `PATCH /admin/users/{id}` (`adminUpdateUser`) | Change an account's app role (last-admin guard; can't self-demote) |
| `DELETE /admin/users/{id}/sessions` (`adminRevokeUserSessions`) | **Force sign-out**: revoke all of an account's active sessions. Because an impersonation token carries the target's account id as its subject, this also ends any in-flight impersonation **of** that account. Audited as `JWT_REVOKED`. |
| `POST /auth/impersonate` (`impersonate`) | Begin impersonating an account (mandatory reason; no self / no admin→admin; read-only by default via `ImpersonationGuard`) |
| `GET /admin/workspaces` (`adminListWorkspaces`) | **Metadata-only** overview of every workspace (slug, status, provider, owner login, member count, created-at). Cross-tenant via `@WorkspaceAgnostic`; this endpoint itself returns **no tenant content**. Content is reached either by impersonating a member or by opening the workspace directly under [elevated access](#elevated-workspace-access); both are audited, and they are different things. |
| `GET /admin/audit` (`adminListAuthEvents`) | Read-only viewer over the append-only `auth_event` log (logins, impersonation, role changes, deletions). Paged, newest-first, filterable by event type; surfaces the `(account_id, acting_account_id)` pair so impersonated actions stay attributable. |
| `GET /admin/config-audit` (`adminListConfigAuditEvents`) | Read-only viewer over `config_audit_event` — who changed which workspace setting, when, and from what to what. Rows are immutable inside the retention window (DB trigger); `ConfigAuditRetentionJob` is the only way one leaves. |
| `/admin/llm/connections*` (`adminListLlmConnections`, `adminCreateLlmConnection`, `adminGetLlmConnection`, `adminUpdateLlmConnection`, `adminDeleteLlmConnection`, `adminProbeLlmConnection`, `adminProbeLlmConnectionDraft`) | The instance LLM connection catalog. Routing identity (base URL, wire API, auth mode) is immutable after create; probe tests a saved or draft connection before anything is enabled. |
| `/admin/llm/models*` (`adminListLlmModels`, `adminCreateLlmModel`, `adminGetLlmModel`, `adminUpdateLlmModel`, `adminDeleteLlmModel`, `adminUpdateLlmModelPrice`, `adminUpdateLlmModelSharing`) | Models under a connection, their prices (temporal supersede-on-insert into `llm_model_price`), and who may use them — public, or granted per workspace. |
| `GET`/`PUT /admin/llm/settings` (`adminGetLlmSettings`, `adminUpdateLlmSettings`) | The instance LLM settings singleton: egress host allowlist and `allowWorkspaceConnections`, the switch that lets workspaces register their own provider connections. |
| `GET /admin/llm/usage` (`adminGetLlmUsageReport`) | Cross-workspace monthly LLM usage and budget report, split by purse (shared models vs each workspace's own provider). |
| `PUT /admin/workspaces/{workspaceSlug}/llm/budget` (`adminUpdateWorkspaceLlmBudget`) | Set **or clear** a workspace's monthly cap on **shared-model** spend — clearing is `PUT` with `monthlyBudgetUsd: null`, not `DELETE` (there is no `DELETE` mapping; it returns 405). The workspace's cap on its own provider is a different endpoint under `/workspaces/**`, set by the workspace's own admin. |
| `GET /admin/settings` / `PATCH /admin/settings/silent-mode` | Read or change the instance-wide outbound delivery brake. Releasing requires `If-Match` with the ETag returned by `GET`, so a stale browser cannot release a newer incident response. |

## Instance Silent Mode

Silent Mode is an emergency and disaster-recovery brake, not a workspace rollout stage. It is
**engaged by default** on new installs, when the singleton settings row is missing, and on upgrades
whose seeded row was never explicitly changed. Detection, observation persistence, inbound webhook
processing, synchronization, and admin access continue, but delivery writes to GitHub, GitLab, and
Slack are refused at the provider gateway.

Suppression is prospective: a suppressed review is recorded as `SUPPRESSED(INSTANCE_SILENCED)` for
audit and preview, but is never queued for replay. Releasing the brake therefore sends nothing by
itself; only a new source event can deliver. Each re-review is a new feedback unit, so a suppressed
re-review is recorded without changing or superseding the last delivered one.

OAuth/token lifecycle operations, webhook registration, and operator alerts remain available while
Silent Mode is engaged.

## Recent sign-in gate

An instance-admin action that changes who can reach what — an app-role change, force sign-out,
beginning an impersonation, any login-provider mutation, registering or removing an LLM connection —
runs only for a caller whose last completed sign-in is younger than
`hephaestus.auth.step-up-max-age` (`HEPHAESTUS_AUTH_STEP_UP_MAX_AGE`, default 5m). Attaching a new
identity to an account is gated the same way, for every user, because a new link is a permanent
second way in.

The sign-in time travels as the standard `auth_time` claim, stamped once by the login that completed
the OAuth dance and copied verbatim by every rotation, so the silent keep-alive can never make a
session look freshly signed in. `SecurityConfig` turns it into Spring Security's
`FactorGrantedAuthority` for the authorization-code factor, and
[`AllRequiredFactorsAuthorizationManager`](https://docs.spring.io/spring-security/reference/servlet/authentication/mfa.html)
compares it against the window. That comparison treats anything not yet expired as valid, so a
session stamped by a pod whose clock leads the enforcing pod's is still fresh — a local clock skew
must never tell an administrator who just signed in to sign in again.

Declaring the requirement is `@RequiresRecentSignIn` on the handler; declining it is
`@RecentSignInExempt(reason = …)` on the handler or its controller. `RecentSignInByDefaultArchTest`
fails the build when an instance-admin mutation carries neither, so a new administrative action
cannot ship without a recorded decision — the same shape `AuditByDefaultArchTest` gives the audit
trail. The refusal is recorded on the `auth_event` type the handler already declares for `@Audited`,
as a `FAILURE` naming the account whose session was refused, and counted as
`auth.step_up.denied{action}`.

### What it does and does not stop

It bounds a **hijacked admin session**: a stolen cookie is only dangerous for the length of the
window, and every attempt it makes is on the trail. It does not stop an attacker who can drive the
browser (XSS, a compromised machine, a session held open alongside the operator's) — they can
complete the confirmation too. It is **not a second factor**: Hephaestus holds no local credential,
so an existing GitHub or GitLab session may satisfy it without any challenge, and MFA stays the
identity provider's responsibility (ADR 0017). It does not stop a malicious administrator, who is
authorised for the action; that is what the audit trail is for.

A refused action is never replayed after the confirmation. The SPA reopens the action so the
operator reviews it and submits it again — a queued privileged write is exactly what an attacker
would want the confirmation to unlock.

## Impersonation time-box

`begin` stamps an absolute ceiling `imp_exp` (`hephaestus.auth.impersonation-max-lifetime`, default
1h); the issuer caps each token's `exp` at `min(now + accessTtl, imp_exp)`, and `refresh` drops the
`act` claim (auto-exit) once it passes. `imp_exp` is the binding limit: the webapp keeps the session
alive across access-token expiry (`use-session-keep-alive.ts`, mounted from `main.tsx`), so an
impersonation ends at the ceiling rather than at `accessTtl`.

## Elevated workspace access

An instance admin who is not a member of a workspace still reaches it as a workspace admin, and both
audit trails say so.

`WorkspaceContextFilter` takes that decision once per request. On the non-member branch it records
the workspace in `WorkspaceElevationContext` — a request-local `ThreadLocal`, cleared in the same
`finally` that clears the workspace context, and deliberately not inheritable, so a task handed to a
background executor starts unelevated. Everything downstream reads the flag from there rather than
from an argument, for the reason `ConfigAuditActor` gives about actor attribution: a producer can
neither forget it nor assert one it did not earn.

- `auth_event` gains a **`WORKSPACE_ELEVATION`** row. It marks an access *window*, not a request:
  `WorkspaceElevationAuditAdapter` de-duplicates per `(account, workspace)` for 15 minutes in a
  bounded per-process cache, so browsing one workspace does not bury the impersonation and
  role-change events the viewer exists for. The cache is claimed only after a row is actually
  written, and eviction or a second replica may add a duplicate marker — over-reporting a window is
  harmless, losing one is not.
- `config_audit_event` gains **`elevated_via_instance_admin`** per row, resolved for that row's own
  `workspaceId`, so an instance-scoped change with no workspace is never mis-tagged. Configuration
  changes are not de-duplicated; every one carries its own bit.

Both admin consoles surface it, and `GET /admin/audit/export` carries it as the **last** CSV column
so a parser keyed on column order keeps working.

Two things the flag does not mean. Impersonation is not elevation — it is attributable through the
`(account_id, acting_account_id)` pair, and an impersonated session that is also elevated carries
both. And `false` means "no elevation recorded", not "the actor was a member": rows written before
the flag existed all read `false`.

## Deferred / follow-up

- **`APP_AUDITOR`** read-only tier: not built. A single-operator instance has no second audience for
  it, and the enum + authority design does not stand in the way of adding one.
LLM governance is not on this list — it is built. An instance admin registers connections and models
under `/admin/llm/*`, prices them, and grants them to workspaces; a workspace may add its own
connection when instance settings permit it. Usage is metered into `llm_usage_event` and capped by two
independent monthly budgets — the instance's cap on shared-model spend and the workspace's cap on its
own provider — which are never summed.
[ADR 0026](https://github.com/ls1intum/Hephaestus/blob/main/docs/decisions/0026-per-purpose-agent-bindings-and-llm-governance.md)
records the decision.
