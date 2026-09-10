# Auth glossary

The ubiquitous language of the `core.auth` module. Where this page and the code disagree, the code
wins: no ArchUnit test enforces this vocabulary, so it is documentation, not a contract.

Two terms below name a *distinction* the code keeps but has not renamed for. The git-provider mirror
is called **external actor** in prose and `integration.scm.domain.user.User` in Java, and the columns
that reference it are still `user_id`. Read the "Lives in" column for the name you can actually grep.

| Term | Meaning | Lives in | Identified by |
|---|---|---|---|
| **`Account`** | The Hephaestus-native principal: one row per human that has authenticated. Carries `app_role`, `status`, contact email, preferences. | `core.auth.domain.Account` | surrogate `id` (BIGINT) |
| **`IdentityLink`** | Federated-login association between an `Account` and an IdP subject; includes `team_id` for Slack-like multi-tenant IdPs. **The only thing login lookup ever queries** — never `email`. | `core.auth.domain.IdentityLink` | surrogate `id`; unique on `(provider_id, subject, COALESCE(team_id, ''))` and partial-unique on `(account_id, provider_id, COALESCE(team_id, '')) WHERE disabled_at IS NULL` |
| **External actor** | Read-only mirror of a git-provider account (USER / ORGANIZATION / BOT). Authors activity (PRs, issues, reviews). **Not a principal** — does not log in, does not have preferences, does not impersonate. | `integration.scm.domain.user.User`; SQL table `"user"` | `(provider_id, native_id)` |
| **Account vs external actor** | Two distinct entities. `WorkspaceAccountMembership`, `AccountFeature`, `IdentityLink`, `oauth_authorized_client`, `issued_jwt` reference `Account`. `Issue.author_id`, `PullRequest.merged_by`, review comments and team memberships reference the external actor. A single login flow may create both: one `Account` (the principal that will hold the cookie) plus one `IdentityLink` whose external-actor reference points to the matching mirror row already synced from the IdP. | — | — |
| **Account workspace membership** | `Account` ↔ `Workspace` with a role, source and sticky suspension. SCM identities are optional and do not hold the authorization fact. The retained `WorkspaceMembership` stores SCM contributor and leaderboard facts instead. See [workspace context](./contributor/workspace-context.mdx#account-identity-resolution). | `workspace.WorkspaceAccountMembership` | unique `(workspace_id, account_id)` |
| **`AccountFeature`** | Per-account feature opt-in. | `core.auth.domain.AccountFeature` | composite `(account_id, flag)` |
| **`AuthEvent`** | Append-only auth / impersonation event. Monthly RANGE-partitioned on `occurred_at`, managed by `pg_partman` (create-ahead + 12-month retention; maintenance via `AuthEventPartitionMaintenance`). Records `(account_id, acting_account_id)` for every impersonation (`act`-claim) action. INSERT-only at the SQL-grant level in non-test environments. | `core.auth.audit.AuthEvent` | composite `(id, occurred_at)` (Postgres partitioning requires it) |
| **Elevated workspace access** | An instance admin reaching a workspace they hold no membership in. Marked once per access window as an `AuthEvent` of type `WORKSPACE_ELEVATION`, and per row as `elevated_via_instance_admin` on both `auth_event` and `config_audit_event`. Distinct from impersonation, which is carried by the `(account_id, acting_account_id)` pair; `false` means "no elevation recorded", never "the actor was a member". Recorded and read in [Instance administration](./contributor/instance-admin.md#elevated-workspace-access). | `core.security.WorkspaceElevationContext`; `core.auth.audit.WorkspaceElevationAuditAdapter` | `(account_id, workspace_id)` of the row |
| **`IssuedJwt`** | Revocation list for Hephaestus-issued cookie JWTs. Inserted at issuance; consulted by `RevocationAwareJwtDecoder` on every request via an indexed `jti` lookup; a *negative* cache holds REVOKED verdicts only (TTL sheds replay load, never a false positive). Effective on every pod within DB lag — no cross-pod protocol. | `core.auth.jwt.IssuedJwt` | `jti` (UUID) |
| **`JwtSigningKey`** | Hephaestus's own ES256 JWT-signing key set. The most recently inserted active key signs every token and every active key verifies; retiring one is a manual act, after which it verifies nothing. Private keys are sealed under the **system** master key (AAD = `system:jwt_signing_key.private_key_pem`) — distinct from the tenant-bound AAD domain used by `CredentialBundleConverter` for per-workspace integration secrets (confused-deputy defense). | `core.auth.jwt.JwtSigningKey` | `kid` |
| **`LoginProvider`** | An instance-scoped OAuth login provider (a sign-in option: GitHub, GitLab.com, self-hosted GitLab). **One per SCM instance** — `UNIQUE(type, base_url)`. Env-seeds the defaults on first boot; an instance admin manages the rest at runtime (`/admin/login-providers`). The client secret is sealed at rest by `EncryptedStringConverter` (AES-256-GCM) and never returned. Authentication only — distinct from a workspace's SCM data-source `Connection`. | `core.auth.provider.LoginProvider` | `registration_id`; `UNIQUE(type, base_url)` |

## Forbidden patterns (ArchUnit-enforced)

- `org.keycloak.*` imports — anywhere, enforced by `NoKeycloakImportTest`.
- `Jwt.getTokenValue()` / `.getToken()` — only inside `core.auth.jwt.*`.
- New `@RestController` without `@Tag`; new public mapping without `@Operation(summary, operationId)`.

`com.auth0.jwt.*` is **not** banned: it signs and verifies the worker-hub tokens
(`core.runtime.hub.auth`) and the GitHub App installation token, and `NoKeycloakImportTest` says so
explicitly. Cookie-session JWTs are a separate path and do not use it.

Login lookup never queries by email — `IdentityLinkRepository` deliberately has no `findByEmail`, the
nOAuth defence. `UserRepository.findByEmail` exists and is reachable, but it resolves a *commit
author* to a mirrored git-provider account; it is not an authentication lookup.

## JWT claim shape

Cookie access tokens combine standard JWT/OIDC claims with application-specific claims:

| Claim | Type | Notes |
|---|---|---|
| `iss` | string | Configured `hephaestus.auth.issuer`. |
| `sub` | string | `Account.id` as decimal. |
| `aud` | string or array | Configured `hephaestus.auth.audience`; defaults to `hephaestus-spa`. |
| `jti` | UUID | Inserted into `issued_jwt`. |
| `iat`, `exp` | Unix seconds | Issued-at and expiry; see [session deadlines](./admin/configuration-readiness.mdx#session-and-impersonation-deadlines). |
| `roles` | array | The account's roles, including `APP_ADMIN`. |
| `preferred_username` | string | The account's login. |
| `given_name` | string | Present when the account has one. |
| `act` | object | Only present when impersonating. Per RFC 8693 — `{ "sub": "<impersonator_account_id>" }`. |
| `imp_exp` | Unix seconds | Only present when impersonating: when the impersonation itself expires. |
| `session_exp` | Unix seconds | When the session, as opposed to this access token, expires. |
| `auth_time` | Unix seconds | Standard OIDC claim: when the account last completed an interactive sign-in here. Stamped at login and copied unchanged through refresh and impersonation, so it measures the age of the sign-in and not of the token. Read by the [recent sign-in gate](./contributor/instance-admin.md#recent-sign-in-gate); it is not evidence that an upstream credential was entered. |

`HephaestusJwtIssuer` emits these claims. `exp` is the earliest of the access-token deadline,
`session_exp`, and `imp_exp` when present. Renewal preserves the absolute deadlines and `auth_time`.
