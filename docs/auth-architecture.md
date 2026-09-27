# Auth architecture

Authentication components and request flow. [The auth glossary](auth-glossary.md) defines the domain
and token claims; [ADR 0017](decisions/0017-replace-keycloak-with-spring-native-auth.md) records the
architecture decision.

## Request flow

```mermaid
sequenceDiagram
    accTitle: Browser sign-in and authenticated requests
    accDescr: The server completes an OAuth exchange, resolves the account by provider and subject, records an issued token, and checks that token against PostgreSQL on subsequent requests.
    participant Browser
    participant Server as Hephaestus server
    participant Provider as GitHub or GitLab
    participant Database as PostgreSQL
    Browser->>Server: Begin sign-in with a configured provider
    Server-->>Browser: Seal login intent and OAuth state in cookies, then redirect
    Browser->>Provider: Authorize with state and PKCE
    Provider-->>Browser: Redirect with authorization code
    Browser->>Server: OAuth callback
    Server->>Provider: Exchange code and load provider identity
    Server->>Database: Resolve or provision account by provider and subject
    Server->>Database: Record issued JWT for revocation
    Server-->>Browser: Set access cookie and redirect to validated return path
    Browser->>Server: Authenticated request with cookie
    Server->>Server: Verify JWT signature and deadlines
    Server->>Database: Check token remains active
    Server-->>Browser: Response subject to authorization and CSRF policy
```

Identity linking uses the same OAuth exchange but binds its sealed intent to the already
signed-in account. It cannot create an authenticated link from an anonymous request. Slack and
Outline support linking only, not sign-in.

Sign-in and linking also fill [`identity_link.external_actor_id`](./auth-glossary.md), the synced
git-provider user the link denotes. User views, Slack identity resolution and account erasure use
this association rather than comparing login names.

## Key properties

- **No HTTP sessions.** Revocation state lives in PostgreSQL. The browser holds one `__Host-HEPHAESTUS_AT`
  cookie carrying an ES256 JWT;
  [session deadlines](./admin/configuration-readiness.mdx#session-deadlines) define
  its lifetime. OAuth-flow state rides AES-GCM cookies, not a
  session — so login works across pods without sticky sessions.
- **Application-issued tokens.** After federating to the upstream IdP, Hephaestus issues its own JWT. Claim
  shape combines standard JWT/OIDC claims with Hephaestus claims, defined in
  [the auth glossary](./auth-glossary.md#jwt-claim-shape) and emitted by `HephaestusJwtIssuer`. The public signing keys are published at `/.well-known/jwks.json`; there is
  no full OIDC discovery document.
- **Two kinds of session, one revocation store.** The web app holds a cookie session; installed
  clients hold a bearer [client session](#installed-clients). Both are rows in `issued_jwt`.
- **Database-backed revocation.** Every issued JWT has a `jti` row in `issued_jwt`. Logout /
  refresh / sign-out-everywhere / account-delete set `revoked_at`; `RevocationAwareJwtDecoder`
  re-checks `issued_jwt(jti)` on every request, so revocation takes effect on every pod within
  DB visibility lag. The cache is a *negative* cache (REVOKED verdicts only) that merely sheds
  replay load; no cross-pod cache invalidation is needed.
- **Account lookup is `(provider, subject)`, never email.** This is the structural defence
  against the nOAuth (Descope 2023) account-takeover class. `IdentityLinkRepository` has no
  `findByEmail`.
- **Login providers are instance-scoped.**
  A sign-in option — GitHub, GitLab.com, or a self-hosted GitLab — is a row in the instance
  `login_provider` table (`core.auth.provider`), **one per SCM instance** (`UNIQUE(type, base_url)`),
  env-seeded on first boot and managed at runtime by an instance admin. The client secret is sealed
  by `EncryptedStringConverter` (AES-256-GCM). This is **authentication** only; a workspace's SCM
  data source is a separate per-workspace `Connection` + group token/PAT. ADR 0017 records why the
  two are separate.
- **A user view never switches authentication.** An instance administrator reads a workspace
  member's private pages as themselves, even when the viewed user has no account — see
  [read-only user views](./contributor/instance-admin.md#read-only-user-views).
- **GDPR.** `auth_event` is an append-only, monthly RANGE-partitioned (self-managed in-app by
  `pg_partman`, 12-month retention; see ADR 0018)
  audit log. Account deletion is a 48-hour soft-delete cooldown → hard cascade +
  pseudonymization of the git-provider mirror (Art. 17(3) — preserves activity-graph integrity on
  other users' work).

## Installed clients

An *installed client* — the Chrome extension, later a mobile app — cannot hold the web app's cookie,
so it signs in to a session of its own. [ADR 0045](decisions/0045-installed-clients-sign-in-with-a-pkce-handoff.md)
records the decision and the options it rejected; `core.auth.clientsession` implements it.

```mermaid
sequenceDiagram
    accTitle: Installed-client sign-in and refresh
    accDescr: The extension starts the normal login with a PKCE challenge, the server completes the provider sign-in, hands a one-minute single-use code to the registered redirect, and exchanges it for a client session whose refresh secret rotates on every use.
    participant Extension as Extension worker
    participant Chrome as Chrome sign-in window
    participant Server as Hephaestus server
    participant Provider as GitHub or GitLab
    participant Database as PostgreSQL
    Extension->>Chrome: launchWebAuthFlow(/auth/login?mode=client, client_id, redirect_uri, S256 challenge, state)
    Chrome->>Server: GET /auth/login
    Server->>Server: Check client id and exact redirect in InstalledClientRegistry
    Server-->>Chrome: Seal client intent in the login-intent cookie, redirect to provider
    Chrome->>Provider: Authorize
    Provider-->>Chrome: Redirect to the server's own OAuth callback
    Chrome->>Server: OAuth callback
    Server->>Database: Resolve account, store hashed handoff (60 s, single use)
    Server-->>Chrome: Redirect to https://<id>.chromiumapp.org/callback?code&state
    Chrome-->>Extension: Callback URL
    Extension->>Server: POST /auth/client/token {clientId, redirectUri, code, codeVerifier}
    Server->>Database: Consume handoff, then verify PKCE
    Server->>Database: Create client_session, record issued JWT with refresh hash
    Server-->>Extension: Access token, refresh secret, session deadline (no-store)
    Extension->>Server: POST /auth/client/refresh {refreshToken}
    Server->>Database: Lock account, then session, then rotate or revoke on reuse
    Server-->>Extension: New tokens, or 401
```

- **One door.** `GET /auth/login` with `mode=client` is the web login with extra, sealed parameters,
  so the provider callback and login providers are shared. An unknown client id or redirect ends on a
  server error page, never a redirect. `GET /auth/dev-login/client` exists only with dev login.
- **Exact redirects from one list.** `InstalledClientRegistry` reads
  `hephaestus.auth.browser-extension-ids`; each id yields one redirect,
  `https://<id>.chromiumapp.org/callback`, and one CORS origin, `chrome-extension://<id>`. An id is a
  routing restriction, not an attestation: ids and their public keys are public.
- **Handoff.** `client_sign_in_handoff` stores only the code's hash, expires after 60 seconds and is
  consumed atomically before the PKCE check, so a wrong verifier burns it. The exchange must present the
  same client id and redirect URI.
- **Session and lineage.** `client_session.id` is the JWT's `sid`. `issued_jwt` rows carry
  `session_id` and a unique `refresh_token_hash` for as long as the session exists, so any refresh
  secret, JTI or logout secret the session ever issued resolves the family.
- **Strict rotation.** Only the current refresh secret renews. Presenting an earlier one revokes the
  session (`REFRESH_REUSE`) and records an auth event; there is no grace window. Copying a secret is
  detected only when a stale one is presented.
- **Lock order account → session → issued_jwt.** Every issuance takes `account FOR SHARE` and
  re-checks the account is active; refresh, logout and single-session revoke then lock the session.
  `SessionRevocation` takes `account FOR UPDATE` before ending client sessions and bulk-revoking issued
  JWTs, so an account-wide revocation cannot race a refresh into a surviving token.
- **Boundaries.** The client endpoints are exact `POST` matchers, CSRF-exempt only without the auth
  cookie, refuse requests that carry it, bound their bodies by pattern and answer `no-store`. The cookie
  `/auth/refresh` refuses `sid` tokens; `/auth/logout` with a `sid` bearer ends the client session.
  User view mints nothing for installed clients.
- **Visible and revocable.** The session list shows a client session once, as its client kind, with its
  latest JTI; revoking any JTI of the family ends the session. Cleanup removes expired handoffs and
  sessions past their deadline or revoked for a day, with their token rows.

## Browser session coordination

Renewal and logout use the browser's
[Web Locks API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Locks_API) to serialize requests
and responses that replace the shared session cookie. Generated SDK operations own HTTP requests;
TanStack Query mutation options retain their lifecycle and error handling. Without Web Locks, only
renewal within a single tab is coordinated.

Rotation atomically revokes the presented token before issuing its replacement. A losing rotation
neither issues nor clears a cookie: its delayed response must not overwrite the winner's credential.
A lost response after committed rotation can require another sign-in. This is the cookie-response
ordering hazard illustrated by [Auth.js issue 8897](https://github.com/nextauthjs/next-auth/issues/8897).

Only an authentication refusal triggers sign-in. Network and server failures preserve the page;
revocation-store failures deny access as server errors. Failed mutations are not automatically
replayed. Logout navigates away only after success or confirmation that the session is already
unauthenticated; otherwise the existing notification surface offers a retryable error.

[Spring Security's `csrf.spa()`](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html#csrf-integration-javascript-spa)
handles SPA CSRF tokens. Cookie-authenticated mutations require CSRF protection, including requests
with an Authorization header; pure bearer requests are exempt. The final filter uses this policy
explicitly because Spring's resource-server exemption also matches cookie-resolved tokens.
Authenticating an existing JWT preserves the CSRF cookie rather than treating each request as a
new sign-in.

Public login-provider discovery ignores credentials so revoked cookies cannot block sign-in.
Locally invalid cookies are ignored without a clearing response that could erase a newer sign-in;
protected endpoints still reject the resulting unauthenticated request.

## Module boundaries

`core.auth` owns accounts, login-provider configuration, OAuth exchange, token issuance and
revocation. Spring Security owns the OAuth and resource-server protocols. The browser uses the
generated API client rather than a second authentication transport.

Login client registrations are built from the instance-scoped `login_provider` store. Integration
adapters must not reach into `core.auth.provider`: they resolve the associated provider record
through `GitProviderRegistry`, an auth SPI. A login provider grants authentication; it does not
supply a workspace's integration credentials.

Workspace, SCM integration and notification modules consume auth read interfaces and events;
auth does not depend on their implementations. HTTP-wide enforcement belongs to `core.security`,
outside individual provider adapters.
