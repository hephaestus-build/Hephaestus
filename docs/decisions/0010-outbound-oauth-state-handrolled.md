# ADR 0010: Hand-roll OAuth state for outbound per-workspace integrations

**Status:** Accepted
**Date:** 2026-05-25
**Authors:** Integration framework polish (#1198)

> **Amendment (2026-05-30):** The PKCE primitive (`issueWithPkce` → `IssuedState`, the
> `code_verifier` nonce column, and the verifier-carrying consume path) was removed. It was
> never wired into any strategy — both providers (GitHub App, Slack OAuth v2) are
> **confidential clients** authenticating with a `client_secret`, for which PKCE is
> recommended-but-not-required (RFC 9700) and provided no active protection while unwired.
> The signed state payload is now `v2|workspaceId|kind|issuedAt|nonce|base64url(accountId)`.
> The CSRF and replay defense (HMAC + TTL + single-use nonce) is unchanged. A future public-client provider that
> needs PKCE can reintroduce the primitive against this same `OAuthStateService` seam.

## Context

Hephaestus opens outbound OAuth Authorization Code flows from per-workspace admin pages
("Connect GitHub", "Connect Slack").
GitLab and Outline connect with a pasted token and have no vendor redirect.
The framework must:

- Survive multi-step flows that may take **minutes** and span **tab close / reopen** (GitHub
  App org-admin approval gates can pause the flow for arbitrary time).
- Bind the resulting credential to a `Connection` row keyed by `(workspaceId, kind,
  instanceKey)` — NOT to the Spring `SecurityContext` principal.
- Run **without a Spring session** at the callback step: the vendor redirects back from
  an unauthenticated browser tab; the only authoritative identity is what we signed into
  `state`.
- Compose with the server's own `oauth2-resource-server` chain (inbound API auth, ADR 0017) —
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
  `installation_id` and a user-authorization `code`.
  The connect exchanges the code for a user token.
  It uses that token only to prove that the installer can access the installation and owns its account.
  The installation token is minted server-side via JWT-signed-with-private-key. Spring's
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
   pin the state token shape in a test. Adopted.

## Decision

**Option 3 (hand-roll), with the small Option 2 alignments** applied where they cost
nothing and reduce future drift:

- Keep `HmacOAuthStateService`, `OAuthStateNonceStore`, the `state` payload shape
  `v2|workspaceId|kind|issuedAt|nonce|base64url(accountId)`, and the single-use
  `consumed_at` conditional UPDATE.
- No PKCE: both providers are confidential clients, so the signed-state HMAC + TTL +
  single-use nonce carry the CSRF/replay defense on their own.
- `HmacOAuthStateServiceTest` pins the state token shape. The OAuth state SPI lives under
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

## Confirmation

`HmacOAuthStateServiceTest` fails when the token shape, the HMAC check or the single-use rule changes.
`OAuthStateNonceStoreIntegrationTest` fails when two consumes of one nonce both succeed.
Nothing fails if a new connect flow uses `spring-boot-starter-oauth2-client`. Review catches that.

## Revisit trigger

Any of:

- Spring Security ships first-class multi-tenant `ClientRegistrationRepository` + a
  per-tenant `OAuth2AuthorizedClientService` schema that keys on something other than
  `principal_name` (track [issue #12862](https://github.com/spring-projects/spring-security/issues/12862)).
- GitHub App flow lands a standard `code` exchange at install time (the callback now carries
  `installation_id` and a user-authorization `code`, see the drivers).
- We reach 10+ integration kinds — at that point the per-kind `ConnectionStrategy`
  branching may itself benefit from a registry-as-`ClientRegistrationRepository`
  refactor, evaluated against Spring's then-current shape.

## References

- [RFC 7636 — PKCE](https://www.rfc-editor.org/info/rfc7636)
- [RFC 9700 — Best Current Practice for OAuth 2.0 Security (Jan 2025)](https://www.rfc-editor.org/rfc/rfc9700.html)
- [spring-security/#12862 — Multi-tenant ClientRegistration enhancement request](https://github.com/spring-projects/spring-security/issues/12862)

## Update — 2026-11-11: the revisit trigger fired

GitHub now sends a `code` with the installation callback, and the connect exchanges it to prove access and ownership.
Spring Security also runs `oauth2Login` beside the resource server on main, with a dynamic client registration repository.
Two reasons of the decision above thus no longer hold.

The open question is whether Spring Security's OAuth2 client replaces the hand-rolled state.
AGENTS.md prefers a framework feature over a hand-rolled one, so that is the default answer.
The question has three inputs:

- binding the state to the browser session (RFC 9700 § 4.7.1)
- PKCE on connect
- the fallback of the state key to the webhook secret.

The question is decided after the 1.0 release. Until then the decision above stays in force.
