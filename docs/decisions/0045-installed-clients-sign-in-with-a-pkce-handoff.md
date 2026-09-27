# ADR 0045: Installed clients sign in with a PKCE handoff to their own revocable session

**Status:** Accepted
**Date:** 2026-09-26
**Authors:** Felix T.J. Dietrich
**Amends:** [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md)

## Context

[ADR 0017](0017-replace-keycloak-with-spring-native-auth.md) made Hephaestus its own token issuer
for exactly one client: the SPA served from the instance origin, holding an ES256 JWT in a
`__Host-` cookie, revoked through `issued_jwt`. It rejected Spring Authorization Server because the
only client was that SPA.

There are now clients that cannot hold that cookie. The Chrome extension runs on
`chrome-extension://<id>`, reads provider pages, and must call the API from its service worker; a
mobile app is designed on the same shape. Neither shares the instance's cookie jar, and neither
should: the cookie's CSRF, rotation and revocation rules assume one browser tab family on one origin.
Each needs to sign in through the instance's existing login providers, receive a credential it keeps
itself, and be revocable by every path that revokes a web session.

A mobile draft had already sketched the core: a sealed native login intent, a 60-second single-use
handoff code, PKCE and an exact redirect. It targeted an endpoint outside the OAuth filter chain and
carried a refresh grace window and impersonation. This decision takes the ideas, not the code.

## Decision drivers

- **One model for every installed client.** The extension now and the mobile app next; a second
  client kind adds a registry entry, not a second auth system.
- **Revocation stays decisive.** Signing out everywhere, an admin revoke, suspension, deletion and
  demotion must end an installed client's session in the same transaction that ends web sessions, with
  no second authority for the decoder to consult.
- **No bearer token in a URL, no cookie crossing origins.** Access and refresh tokens never appear in
  a redirect's query or fragment, where history, logs or a referrer can keep them; the extension never
  reads the instance's cookies. The one thing a redirect does carry is the sign-in handoff code and
  its `state` in the callback query, which is deliberate: the code is single-use, expires within a
  minute and is bound by PKCE to the verifier only the client holds, so a copy of the URL cannot be
  redeemed.
- **Provider configuration unchanged.** Operators must not register a callback per client at GitHub or
  GitLab.
- **The smallest mechanism that meets RFC 9700 and RFC 8252** for public clients: PKCE `S256`, exact
  redirect matching, refresh rotation with reuse detection.

## Considered options

1. **Mount Spring Authorization Server** as a second issuer for public clients. Standard endpoints,
   but it brings its own client, authorization and consent stores, a second token format beside the
   one `RevocationAwareJwtDecoder` checks, and its session-backed authorization endpoint conflicts with
   the stateless chains. Revocation would have to be bridged between two stores — the property this
   decision exists to keep. Rejected.
2. **Share the web session cookie** with the extension through the `cookies` permission, or a page
   that forwards it. Hands a public client the SPA's credential, bypasses CSRF assumptions, cannot be
   listed or revoked on its own, and is not available to a mobile app at all. Rejected.
3. **Personal access tokens** pasted into the extension. Long-lived, copied by hand, shown in a
   settings page, and a support burden to rotate; they skip the login providers entirely. Rejected.
4. **Rotation with a grace window**, accepting the previous refresh secret for a few seconds so a
   lost response does not sign the client out. A window in which a stolen secret is valid is a window
   in which reuse is indistinguishable from a retry. The extension single-flights refresh in its
   worker, so the window buys nothing it needs. Rejected; a lost refresh response means signing in
   again.
5. **An auth system per client kind** — one flow for the extension, another for mobile. Two sets of
   endpoints and two revocation paths to keep in step. Rejected.
6. **A PKCE handoff on the existing login door into a server-side client session** (this ADR).

## Decision

Option 6.

- **The door is `GET /auth/login` with `mode=client`**, already on the OAuth filter chain, carrying
  `client_id`, `redirect_uri`, `code_challenge`, `code_challenge_method=S256` and `state`. The
  parameters are validated and sealed into the existing AES-GCM login-intent cookie before the provider
  redirect, so the provider callback stays on the server and nothing changes at the provider. A dev
  twin, `GET /auth/dev-login/client`, exists only while dev login is enabled.
- **Clients are registered, and redirects are exact.** `InstalledClientRegistry` is built from
  `hephaestus.auth.browser-extension-ids`. Each extension id yields exactly one redirect,
  `https://<id>.chromiumapp.org/callback`, and one CORS origin, `chrome-extension://<id>`, so callback
  and CORS lists cannot drift. An unknown client or redirect ends on a server error page, never a
  redirect. `InstalledClientKind` has `BROWSER_EXTENSION`; a mobile kind adds its own redirect list.
- **The handoff is a 60-second, single-use, hashed code.** After the provider sign-in the server
  stores `client_sign_in_handoff` keyed by the code's hash and redirects to the registered URI with
  the code and `state`. `POST /auth/client/token` presents the code, the verifier, and the same client
  id and redirect URI. The row is consumed atomically *before* the PKCE check, so a wrong verifier burns
  the code.
- **The result is a `client_session`** (`id` = the JWT's `sid` claim) owned by the account, with the
  client kind and id, the absolute session deadline and the original `auth_time`. Tokens carry `sid`;
  the access token is the same ES256 JWT the web app uses, sent as a bearer, and returned only in
  response bodies marked `Cache-Control: no-store`.
- **The whole refresh lineage stays on `issued_jwt`.** Rows gain `session_id` and a unique
  `refresh_token_hash`. Every token a session issued keeps its hash until the session is gone, so any
  old refresh secret, old JTI or logout secret resolves its family.
- **Rotation is strict.** `POST /auth/client/refresh` accepts only the current secret. Presenting any
  earlier secret of a live session is reuse: the session is revoked (`REFRESH_REUSE`) and an auth event
  recorded. A copied secret is detected only when it, or the original, is presented after the other
  has rotated; copying itself is not observable. `POST /auth/client/logout` ends the session. The cookie `/auth/refresh` refuses `sid`
  tokens, and `/auth/logout` with a `sid` bearer ends the session.
- **One lock order: account → session → issued_jwt.** Every issuance takes `account FOR SHARE` and
  re-checks the account is active; client refresh, logout and single-session revoke then lock the
  session `FOR UPDATE`. Account-wide revocation goes through one `SessionRevocation` method that takes
  `account FOR UPDATE` first, ends client sessions and bulk-revokes issued JWTs in one transaction.
  Revocation therefore cannot race an in-flight refresh into a surviving token.
- **Client sessions are sessions.** The session list shows each once, as its client kind, with its
  latest JTI; revoking any JTI of the family ends the session. The access-token lifetime, the absolute
  session deadline and the consent gate are unchanged.
- **The public endpoints are narrow.** Exact `POST` matchers only, CSRF-exempt only without the auth
  cookie, a request carrying the auth cookie refused, bodies bounded by pattern. `GET
  /auth/client/configuration?clientId=` answers whether an id is registered.

## Consequences

**Positive**

- One revocation authority for every client. `RevocationAwareJwtDecoder` keeps its single indexed
  lookup; nothing new is consulted per request.
- Operators allow a client with one variable and change nothing at their providers.
- A mobile app reuses the handoff, session and rotation unchanged and adds a registry entry.
- Refresh reuse is detected rather than tolerated, and ends only the affected session.

**Negative**

- A lost refresh response signs the client out. Acceptable for an extension whose sign-in already
  ends when Chrome closes; a mobile client with flakier networks may push on this, which is the revisit
  trigger below.
- We own more of an authorization server: a handoff table, a session table, lineage columns and their
  cleanup. They are small and tested against PostgreSQL, including both lock acquisition orders.
- Chrome's `chromiumapp.org` redirect ties the extension's callback to its id, and an id is public:
  any build carrying the same public key has it. The registry is therefore a routing restriction, not
  an attestation of the package; the controls are PKCE with no client secret, the exact redirect and
  the single-use handoff, and which packages users run is managed distribution. The development id,
  shared by every contributor's build, is never allowed in production.

**Neutral**

- ADR 0017's "no Spring Authorization Server" stands; its escape hatch — mounting SAS as a second
  issuer if third-party clients appear — is still the path for *third-party* clients. Installed
  clients published by this project are not third-party.

## Revisit trigger

- A third-party client needs to sign in, which needs consent screens and dynamic registration: mount
  an authorization server instead of growing this one.
- The mobile client's measured sign-out rate from lost refresh responses is material, which would
  reopen option 4 with evidence.
- Chrome changes how `identity.launchWebAuthFlow` redirects, or offers a platform credential the
  extension could hold instead.
