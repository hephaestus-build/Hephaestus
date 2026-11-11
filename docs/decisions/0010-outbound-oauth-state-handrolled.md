# ADR 0010: Hand-roll OAuth state for outbound per-workspace integrations

**Status:** Accepted (amended 2026-05-30 — PKCE primitive removed)
**Date:** 2026-05-25
**Authors:** Integration framework polish (#1198)

> **Amendment (2026-05-30):** The PKCE primitive (`issueWithPkce` → `IssuedState`, the
> `code_verifier` nonce column, and the verifier-carrying consume path) was removed. It was
> never wired into any strategy — both providers (GitHub App, Slack OAuth v2) are
> **confidential clients** authenticating with a `client_secret`, for which PKCE is
> recommended-but-not-required (RFC 9700) and provided no active protection while unwired.
> The signed state payload is now `(workspaceId|kind|issuedAt|nonce|actorRef)`; CSRF/replay
> defense (HMAC + TTL + single-use nonce) is unchanged. A future public-client provider that
> needs PKCE can reintroduce the primitive against this same `OAuthStateService` seam.

## Context

Hephaestus opens outbound OAuth Authorization Code flows from per-workspace admin pages
("Connect GitHub", "Connect GitLab", "Connect Slack"; Outline integration was
removed from this epic — see ADR 0015). The framework
must:

- Survive multi-step flows that may take **minutes** and span **tab close / reopen** (GitHub
  App org-admin approval gates can pause the flow for arbitrary time).
- Bind the resulting credential to a `Connection` row keyed by `(workspaceId, kind,
  instanceKey)` — NOT to the Spring `SecurityContext` principal.
- Run **without a Spring session** at the callback step: the vendor redirects back from
  an unauthenticated browser tab; the only authoritative identity is what we signed into
  `state`.
- Compose with the existing Keycloak `oauth2-resource-server` chain (inbound API auth) —
  Spring Security 6 cannot run resource-server + client filters with conflicting state
  stores against the same `/oauth/**` paths.
- Support **per-instance** ClientRegistration shapes (each Slack workspace has its own
  signing secret; each GitLab self-hosted instance has its own `client_id`).

We examined whether `spring-boot-starter-oauth2-client` could replace the hand-rolled
`HmacOAuthStateService` + `OAuthStateNonceStore` plumbing.

## Decision drivers

- **Workspace-keyed token persistence**, not principal-keyed. `OAuth2AuthorizedClientService`'s
  schema has a composite key `(client_registration_id, principal_name)`; hacking
  `workspaceId` into `principal_name` collapses two distinct concepts and silently re-keys
  Spring's intended invariants. See [JdbcOAuth2AuthorizedClientService](https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/oauth2/client/JdbcOAuth2AuthorizedClientService.html).
- **Multi-minute, multi-tab flows.** Spring's default `HttpSessionOAuth2AuthorizationRequestRepository`
  binds state to a session that doesn't survive an admin closing the tab during GitHub
  App approval. Replacing it with a JDBC-backed `AuthorizationRequestRepository` is exactly
  re-implementing `OAuthStateNonceStore` behind Spring's interface — same code, less power
  (no per-row TTL queries, no workspace cross-referencing).
- **Dynamic per-tenant ClientRegistration is not first-class.**
  [spring-security#12862](https://github.com/spring-projects/spring-security/issues/12862)
  is an open enhancement request. Workarounds require a custom `ClientRegistrationRepository`
  with careful concurrent-modification handling. Same effort as hand-rolled; less control.
- **GitHub App ≠ standard OAuth Authorization Code.** GitHub App install callbacks deliver
  `installation_id`, not `code`. There is no token-exchange at the browser callback — the
  installation token is minted server-side via JWT-signed-with-private-key. Spring's
  `OAuth2LoginAuthenticationFilter` at `/login/oauth2/code/{registrationId}` is the wrong
  endpoint shape entirely.
- **Both providers are confidential clients.** GitHub App and Slack OAuth v2 both
  authenticate the token exchange with a `client_secret`, so PKCE is recommended-but-not-
  required (RFC 9700 § 2.1.1) rather than load-bearing. We do not implement PKCE; CSRF/replay
  defense rests entirely on the signed-state HMAC + TTL + single-use nonce. A future
  public-client provider that genuinely needs PKCE can layer it onto this same
  `OAuthStateService` seam.

## Considered options

1. **Full replace** — rewrite using Spring Security OAuth2 Client end-to-end. Rejected.
   The session-binding, principal-keyed persistence, and single-vendor-flow assumptions
   are baked too deep. Net code reduction is negative once the per-tenant
   `ClientRegistrationRepository` + JDBC `AuthorizationRequestRepository` + custom
   `OAuth2AuthorizedClientService` are written.

2. **Hybrid** — keep the hand-rolled state HMAC + nonce, but adopt Spring's vocabulary
   where it overlaps:
   - Reuse Spring naming for the binding fields (`StateBinding`) so a future migration
     (if the OSS landscape closes the multi-tenant gap) is a refactor, not a rewrite.

3. **Hand-roll** the entire flow, document our cryptographic choices in line with RFC 9700,
   ArchUnit-pin the state token shape. Adopted.

## Decision

**Option 3 (hand-roll), with the small Option 2 alignments** applied where they cost
nothing and reduce future drift:

- Keep `HmacOAuthStateService`, `OAuthStateNonceStore`, the `state` payload shape
  `(workspaceId|kind|issuedAt|nonce|actorRef)`, and the single-use
  `consumed_at` conditional UPDATE.
- No PKCE: both providers are confidential clients, so the signed-state HMAC + TTL +
  single-use nonce carry the CSRF/replay defense on their own.
- ArchUnit pins remain: the OAuth state SPI lives under
  `integration/core/oauth/state/`.

## Consequences

**Positive:**

- Workspace-keyed credential persistence keeps the `Connection` aggregate as the single
  authoritative store. No second source of truth via `OAuth2AuthorizedClient`.
- Multi-minute, multi-tab flows work because the nonce row is persistent (DB-backed,
  TTL-pruned via `OAuthStateNonceCleanupJob`).
- New vendor integrations plug into `ConnectionStrategy` without touching Spring Security
  config. The framework can add Linear/Jira/etc. without registering a `ClientRegistration`
  bean per tenant.

**Neutral:**

- Vocabulary aligned with Spring Security where it overlaps: `StateBinding` follows
  Spring naming so future migration (if the OSS landscape closes the multi-tenant gap)
  is a refactor, not a rewrite.

**Negative:**

- Maintain ~600 LOC of OAuth state plumbing that Spring would have provided for the
  session-bound case. We accept this cost to keep workspace-binding clean.
- Future engineers will ask "why don't we use `spring-boot-starter-oauth2-client`?".
  This ADR is the standing answer.

## Revisit trigger

Any of:

- Spring Security ships first-class multi-tenant `ClientRegistrationRepository` + a
  per-tenant `OAuth2AuthorizedClientService` schema that keys on something other than
  `principal_name` (track [issue #12862](https://github.com/spring-projects/spring-security/issues/12862)).
- GitHub App flow lands a standard `code` exchange at install time (currently `installation_id`
  is the callback param, not `code`).
- We reach 10+ integration kinds — at that point the per-kind `ConnectionStrategy`
  branching may itself benefit from a registry-as-`ClientRegistrationRepository`
  refactor, evaluated against Spring's then-current shape.

## Update 2026-11-11: the callback is bound to the browser that started it

The signed state proves who started a connect flow, not which browser returns with it.
A link to a started flow, opened in another browser, could connect that browser's provider account to the starter's workspace.
RFC 9700 §2.1 requires "one-time use CSRF tokens carried in the state parameter that are securely bound to the user agent".
OWASP ASVS 5.0 10.1.2 requires values that "are securely bound to both the client and the user agent session".

- `ConnectionController.initiate` sets a host-only `__Host-CONNECT_<hash>` cookie when it redirects to a provider.
  The cookie holds the SHA-256 of the state, never the state itself, and lives as long as the state (`hephaestus.integration.oauth-state.ttl`).
- `OAuthCallbackController` accepts a state only with that cookie, before it consumes the state.
  Without the cookie, the callback answers `browser_unconfirmed` and leaves the state for the browser that started the flow.
  The Slack cancellation, which consumes a state to return to its workspace, also needs the cookie.
  Each callback that carries a state clears the cookie.
- The API and the callback share the apex host (ADR 0053), and the provider's redirect is a top-level navigation, so a `SameSite=Lax` cookie reaches the callback.
- No legitimate flow returns with a state in another browser.
  A GitHub installation that an organization owner approves arrives without a state and takes the setup path.

PKCE is not used: no connect flow starts an authorization request that the provider documents with `code_challenge`.
GitHub starts the user authorization itself: "GitHub will start the web application flow immediately after a user installs your app", and that authorize URL carries only `client_id`.
Slack's authorize parameters list no `code_challenge`.
GitLab and Outline use pasted tokens.
The state cookie gives the browser binding that PKCE would give (RFC 9700 §2.1.1 recommends PKCE for confidential clients).

## References

- [RFC 7636 — PKCE](https://www.rfc-editor.org/info/rfc7636)
- [RFC 9700 — Best Current Practice for OAuth 2.0 Security (Jan 2025)](https://www.rfc-editor.org/rfc/rfc9700.html)
- [spring-security/#12862 — Multi-tenant ClientRegistration enhancement request](https://github.com/spring-projects/spring-security/issues/12862)
- [OWASP ASVS 5.0 — V10 OAuth and OIDC](https://github.com/OWASP/ASVS/blob/master/5.0/en/0x19-V10-OAuth-and-OIDC.md)
- [GitHub Docs — Generating a user access token for a GitHub App](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app)
- [Slack — Installing with OAuth](https://docs.slack.dev/authentication/installing-with-oauth)
