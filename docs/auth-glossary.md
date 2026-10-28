# Auth glossary

The identity terms used by the `core.auth` module.

Native accounts identify people for authentication, membership and account-wide choices.
Provider actors retain source authorship, including bots that have no native account.
The implementation column gives exact code names for those separate concepts.

| Term | Meaning | Lives in | Identified by |
|---|---|---|---|
| **`Account`** | The Hephaestus-native principal: one row per human that has authenticated. Carries `app_role`, `status`, contact email, preferences. | `core.auth.domain.Account` | surrogate `id` (BIGINT) |
| **Identity** | A provider login linked to a native account. Lookup uses the exact provider, native key and team key. Disabled authority survives roster sync. | `core.auth.domain.Identity` | Active provider/native/team identity is unique |
| **External actor** | Read-only mirror of a git-provider account (USER / ORGANIZATION / BOT). Authors activity (PRs, issues, reviews). **Not a principal** — does not authenticate. Activity references this row. Native account choices do not. | `integration.scm.domain.user.User`; SQL table `"user"` | `(provider_id, native_id)` |
| **Account vs provider actor** | Accounts own membership, native choices and authentication. Provider actors own authored source activity and may be bots. Exact identity resolution links human activity to an account without changing provider facts. | Native account and SCM user mirror | Different keys and authority |
| **Provider actor** | A mirror of source attribution, including bots. It is not the workspace principal. Exact provider/native identity resolves its account when appropriate. | `integration.scm.domain.user.User` | Provider and native identity |
| **Membership** | Joins an account to a workspace with a role, review selection and retained left state. | `workspace.Membership` | Workspace and account |
| **`AccountFeature`** | Per-account feature opt-in. | `core.auth.domain.AccountFeature` | composite `(account_id, flag)` |
| **`AuthEvent`** | Append-only authentication and privileged-access event. Monthly RANGE-partitioned on `occurred_at`, managed by `pg_partman` (create-ahead + 12-month retention; maintenance via `AuthEventPartitionMaintenance`). `viewed_actor_id` keeps the exact provider disclosure while `viewed_account_id` records the native account when present; [read-only user views](./contributor/instance-admin.md#read-only-user-views) has the row's attribution. INSERT-only at the SQL-grant level in non-test environments. | `core.auth.audit.AuthEvent` | composite `(id, occurred_at)` (Postgres partitioning requires it) |
| **Elevated workspace access** | An instance admin reaching a workspace they hold no membership in. Marked once per access window as an `AuthEvent` of type `WORKSPACE_ELEVATION`, and per row as `elevated_via_instance_admin` on both `auth_event` and `config_audit_event`. Distinct from a [user view](./contributor/instance-admin.md#read-only-user-views), which names the viewed user in `viewed_account_id`; `false` means "no elevation recorded", never "the actor was a member". Recorded and read in [Instance administration](./contributor/instance-admin.md#elevated-workspace-access). | `core.security.WorkspaceElevationContext`; `core.auth.audit.WorkspaceElevationAuditAdapter` | `(account_id, workspace_id)` of the row |
| **`IssuedJwt`** | Revocation list for Hephaestus-issued JWTs, cookie and installed-client alike; an installed client's rows also carry `session_id` and `refresh_token_hash`. A renewed browser token retains `predecessor_jti` and records its first use in `used_at`; [the rotation contract](./auth-architecture.md#rotation-contract) gives the rules. Inserted at issuance; consulted by `RevocationAwareJwtDecoder` on every request via an indexed `jti` lookup; a *negative* cache holds REVOKED verdicts only (TTL sheds replay load, never a false positive). Effective on every pod within DB lag — no cross-pod protocol. | `core.auth.jwt.IssuedJwt` | `jti` (UUID) |
| **Installed client** | A first-party application that cannot hold the web app's cookie and signs in to a session of its own: the Chrome extension (`BROWSER_EXTENSION`), later a mobile app. Registered by id; each id yields exactly one redirect and one CORS origin. Not a third-party OAuth client. [ADR 0048](decisions/0048-installed-clients-sign-in-with-a-pkce-handoff.md). | `core.auth.clientsession.InstalledClientRegistry`, `InstalledClientKind` | client id (the Chrome extension id) |
| **Sign-in handoff** | The one-time code an installed client receives at its registered redirect after the provider sign-in, exchanged with its PKCE verifier for tokens. Only its hash is stored; valid 60 seconds, consumed once, burned by a wrong verifier. | `core.auth.clientsession`; table `client_sign_in_handoff` | `code_hash` |
| **Client session** | An installed client's sign-in: owned by an account, bounded by the same absolute deadline as a web session, carrying the original `auth_time`. Its tokens carry `sid`; its whole refresh lineage stays on `issued_jwt`. Listed in the user's sessions as its client kind and ended by every revocation path; reuse of a replaced refresh secret revokes it. | `core.auth.clientsession`; table `client_session` | `id` (UUID) = the `sid` claim |
| **`JwtSigningKey`** | Hephaestus's own ES256 JWT-signing key set. The most recently inserted active key signs every token and every active key verifies; retiring one is a manual act, after which it verifies nothing. Private keys are sealed under the **system** master key (AAD = `system:jwt_signing_key.private_key_pem`) — distinct from the tenant-bound AAD domain used by `CredentialBundleConverter` for per-workspace integration secrets (confused-deputy defense). | `core.auth.jwt.JwtSigningKey` | `kid` |
| **`LoginProvider`** | An instance-scoped OAuth login provider (a sign-in option: GitHub, GitLab.com, self-hosted GitLab). **One per SCM instance** — `UNIQUE(type, base_url)`. Env-seeds the defaults on first boot; an instance admin manages the rest at runtime (`/admin/login-providers`). The client secret is sealed at rest by `EncryptedStringConverter` (AES-256-GCM) and never returned. Authentication only — distinct from a workspace's SCM data-source `Connection`. | `core.auth.provider.LoginProvider` | `registration_id`; `UNIQUE(type, base_url)` |

## Forbidden patterns (ArchUnit-enforced)

- `org.keycloak.*` imports — anywhere, enforced by `NoKeycloakImportTest`.
- `Jwt.getTokenValue()` / `.getToken()` — only inside `core.auth.jwt.*`.
- New `@RestController` without `@Tag`; new public mapping without `@Operation(summary, operationId)`.

`com.auth0.jwt.*` is **not** banned: it signs and verifies the attempt tokens of sandboxes
(`agent.attempt`) and the GitHub App installation token, and `NoKeycloakImportTest` says so
explicitly. Cookie-session JWTs are a separate path and do not use it.

Login lookup never queries by email — the identity repository deliberately has no `findByEmail`, the
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
| `iat`, `exp` | Unix seconds | Issued-at and expiry; see [session deadlines](./admin/configuration-readiness.mdx#session-deadlines). |
| `roles` | array | The account's roles, including `APP_ADMIN`. |
| `preferred_username` | string | The account's login. |
| `given_name` | string | Present when the account has one. |
| `session_exp` | Unix seconds | When the session, as opposed to this access token, expires. |
| `auth_time` | Unix seconds | Standard OIDC claim: when the account last completed an interactive sign-in here. Stamped at login and copied unchanged through refresh, so it measures the age of the sign-in and not of the token. Read by the [recent sign-in gate](./contributor/instance-admin.md#recent-sign-in-gate); it is not evidence that an upstream credential was entered. |
| `sid` | UUID | Only on installed-client tokens: the client session they belong to. The cookie `/auth/refresh` refuses a token that carries it. |

`HephaestusJwtIssuer` emits these claims. `exp` is the earliest of the access-token deadline,
`session_exp`. Renewal preserves the absolute deadlines and `auth_time`. `RevocationAwareJwtDecoder`
rejects any token carrying an `act` claim: nothing here acts for another account.
