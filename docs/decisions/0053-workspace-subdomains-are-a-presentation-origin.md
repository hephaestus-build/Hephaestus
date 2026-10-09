# ADR 0053: A workspace subdomain is a presentation origin, and sign-in stays on the apex

**Status:** Accepted
**Date:** 2026-10-09
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md) (cookie session),
[ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md) (sign-in handoff)

## Context

A workspace lives at `/w/<slug>` on the instance host.
Open-source projects want their own address, such as `artemis.hephaestus.build`.
They also want the [public activity page](0052-activity-sorts-by-contributions-and-can-be-public.md) at the root of that address.

Sign-in and the session are on the apex host.
The session is an ES256 JWT in `__Host-` cookies (ADR 0017).
A `__Host-` cookie has no `Domain` attribute, so the browser sends it only to the host that set it [1].
GitLab and Outline accept only exact redirect URIs.
OAuth security guidance requires exact redirect matching [2].
Today, the OAuth redirect URI uses `{baseUrl}`, which follows the `Host` header.

`<slug>.hephaestus.build` and `hephaestus.build` are different origins but the same site [3].
A site is the registrable domain, which the Public Suffix List defines [4].
Thus, a credentialed request from a workspace host to the apex sends the apex cookies.
The server must allow the origin with `Access-Control-Allow-Credentials: true` [5].

## Decision drivers

- One sign-in, one session, and unchanged `__Host-` cookies.
- No change at the providers: one exact callback for each provider.
- Tenancy stays a server and SQL rule. A host name never grants access.
- One instance switch turns the feature on and off.
- A self-hosted instance changes nothing until its operator turns the feature on.

## Considered options

1. **Paths only, as today.** Rejected. A project gets no own address, and the public page cannot be at a root.
2. **A domain cookie (`Domain=hephaestus.build`) for every subdomain.** Rejected. It drops the `__Host-` prefix. Every subdomain, also docs and previews, then receives the session cookie. Any subdomain can also set a cookie for all others.
3. **A sign-in broker for each host.** Each workspace host runs its own sign-in and session, for example through the ADR 0048 handoff. Rejected. It adds a session for each host and a sign-out that must reach every host. It also adds a CSRF state and a callback path for each host.
4. **A custom domain for each workspace, such as `contributors.example.org`.** Rejected for now. It is a different site, so the browser treats the apex cookies as third-party cookies. It also needs a certificate for each domain.
5. **The subdomain is a presentation origin. Sign-in and the API stay on the apex.** Chosen.

## Decision

Option 5.

- When the instance switch is on, `<slug>.<base domain>` is the main workspace address.
  `/w/<slug>` sends a 308 redirect to it.
  When the switch is off, `/w/<slug>` is the only address.
- A workspace host serves only the web app. It has no `/api`.
  The web app calls `https://<apex>/api` with credentials.
- Sign-in stays on the apex.
  The OAuth redirect URI is pinned to the apex, with one exact callback for each provider.
  The GitHub App's wildcard redirect toggle is off.
- CORS allows an origin only if all of these are true:
  - It is HTTPS and matches an anchored pattern for the base domain.
  - Its label is not reserved. `docs` is never allowed.
  - Its label is a known workspace slug.

  The response allows credentials and sends `Vary: Origin`.
- A workspace host cannot read the apex CSRF cookie.
  Thus, an endpoint returns the raw CSRF token.
  The web app fetches it again after each sign-in and sign-out.
- `ReturnToValidator` stays relative-only.
- A slug is a valid DNS label in ASCII: letters, digits and inner hyphens, with no `--`.
  Reserved names include `www`, `api`, `docs`, `admin`, `auth`, `login`, `mail`, `status`, `staging`, `preview`, `pr<digits>`, and the RFC 2142 mailbox names.
  A used slug is never given to another workspace.
  A rename redirects through the slug history.
- Each origin keeps its own browser state: cookie choices, theme, and the admin **View as user** session.
  The Slack connect flow carries its return slug in the signed OAuth state.
- The base domain must not be on the Public Suffix List.
  If it is, the workspace host and the apex are different sites.
  The browser then does not send the session cookie.
- hephaestus.build serves its hosts through a Cloudflare proxy.
  Cloudflare is then a new processor for all traffic that it proxies.
  The [processor checklist](../admin/dsms/processor-checklist.md) records it before it carries traffic.
- The browser extension maps a pasted workspace host URL to the apex.

## Consequences

- A project gets its own address, and the public page is at its root.
- Sign-in, the session and the provider configuration do not change.
- The credentialed CORS allowlist and the raw CSRF token endpoint are a new attack surface.
  The anchored origin check and the slug lookup limit them to known workspace hosts.
- All workspace hosts are the same site as the apex.
  Thus, `SameSite` gives no protection between them, and the CSRF token stays necessary.
- A reader answers the cookie choice once on each workspace host.
- The decision is reversible.
  Turn off the instance switch, and `/w/<slug>` is the address again.
  No schema or stored data depends on the host.
  If central sign-in stops working, the ADR 0048 handoff can give a workspace host its own session.
  That needs no new auth system.

## Revisit trigger

- A browser stops sending same-site cookies on a credentialed cross-origin request.
- The base domain must go on the Public Suffix List.
- A workspace asks for a custom domain.
- A workspace host needs its own session, for example for a separate sign-in provider.

## Sources

1. MDN, *Set-Cookie*, cookie prefixes and the `Domain` attribute: <https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie>
2. RFC 9700, *Best Current Practice for OAuth 2.0 Security*, section 2.1: <https://www.rfc-editor.org/rfc/rfc9700#section-2.1>
3. MDN, *Site*: <https://developer.mozilla.org/en-US/docs/Glossary/Site>
4. Public Suffix List, *Learn more*: <https://publicsuffix.org/learn/>
5. MDN, *Access-Control-Allow-Credentials*: <https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Access-Control-Allow-Credentials>
