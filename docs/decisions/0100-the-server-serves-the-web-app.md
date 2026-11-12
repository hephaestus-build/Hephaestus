# ADR 0100: The server serves the web app

**Status:** Accepted
**Date:** 2026-11-11
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md) § The runtime that follows: the web app no longer keeps its nginx image.

**Builds on:** [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) (the chart is the only deployment) and [ADR 0070](0070-1-0-is-a-cutover-release.md) (1.0 is a cutover).

## Context

The web app is a React 19 single-page app (SPA) that renders in the browser.
It uses Vite, TanStack Router and TanStack Query.
Until this decision, it shipped as its own nginx image:

- At start, a 183-line bash entrypoint read the TypeScript `RuntimeEnvVars` interface with `grep`.
  It wrote `index.html`, `env-config.<hash>.js`, `.well-known/hephaestus.json` and `security.txt`.
- `webapp/docker/nginx.conf` (219 lines, four included files) held a Content Security Policy (CSP), the SPA fallback, the `/mentor` redirects, the workspace-host guard and `access_log off`.
- The Gateway sent `/` to nginx. It sent `/api` to the server and removed the prefix (`URLRewrite`).
  The server added the prefix back to its OAuth URLs (`hephaestus.auth.api-base-path`).

This split had four measured costs (lane WEBARCH, 2026-11-11, `origin/main` `9a0ae28699`):

1. **Two header policies that differ.** nginx sent `Referrer-Policy: no-referrer`, and Spring Security sent `strict-origin-when-cross-origin`.
   Only Spring sent `Cross-Origin-Embedder-Policy`.
   The Gateway set HSTS for 30 days, and Spring set it for one year.
2. **The tested artifact was not the shipped artifact.** The browser suite ran `vp preview` (`webapp/playwright.config.ts`).
   Vite says that `vite preview` is "not meant as a production server" [S11].
   Only a 453-line harness that simulates the image tested the shipped CSP, fallback and entrypoint.
   This breaks rule 4 of `AGENTS.md`.
3. **A second image and a second advisory stream.** nginx had 26 advisories with 2026 IDs, some in `map` and `rewrite`, which the config used [S28].
   The webapp image needed 8 VEX entries.
4. **No strict CSP.** A static file cannot carry a per-request nonce, so the policy stayed at `script-src 'self'`.
   OWASP calls a strict CSP "current leading practice" [S20], and web.dev says to use a nonce for pages that a server renders [S21].

Before the change, the first load needed two blocking classic scripts (`env-config`, `theme-init`).
The critical path was 449 KB with gzip.
Brotli at level 11 makes it 370 KB (−18 %).

## Decision drivers

- One header policy, with one home.
- The browser suite tests the artifact that ships (AGENTS.md rule 4).
- One image and one Deployment for self-hosters, before 1.0 freezes the chart values (ADR 0070).
- A strict CSP must be possible.
- Personalized pages: every useful page needs the signed-in account.

## Considered options

A. **Keep nginx.** nginx is an excellent static server, and the shell loads while the server is down.
   But the shell without the API shows only an error, and the four costs above stay.

A′. **Keep nginx, and take the runtime config from a typed server endpoint.** Mattermost and Argo CD work this way [S51, S55].
   It deletes the bash, but it adds a round trip before the first render and keeps costs 1–4.

B. **The server JAR and image serve the built SPA.** This is the usual pattern for self-hosted dashboards: Grafana, Argo CD, Immich since v1.88, Mattermost, Keycloak, Gitea, Authentik and Mastodon [S50, S51, S53, S55–S57].
   Spring serves precompressed `.br` and `.gz` files [S40] and marks hashed assets `immutable` [S41].
   The server renders `index.html` with a typed config record as `<script type="application/json">`, as Keycloak does [S56].
   Spring Security 7.2 adds a per-request CSP nonce [S26], and Vite writes it into every script tag (`html.cspNonce`) [S10].

C. **TanStack Start with server-side rendering (SSR).** SSR sends HTML before JavaScript.
   But web.dev says that personalized pages do not suit static rendering, that SSR can increase time to first byte, and that hydration hurts interaction latency [S1].
   It adds a Node runtime with its own advisory stream and a third header policy.
   The migration takes weeks and is hard to reverse.

C′. **TanStack Start in SPA mode, or prerendering only.** It gives the same result as B, plus prerendered public pages.
   It adds a framework for a small gain. B does not prevent it later, because `dist` stays a static folder.

D. **A CDN or an edge backend for the frontend.** It is fast worldwide, but a university cluster has no CDN.
   The default must not need one. Hashed, immutable assets under B let an operator add a CDN.

## Decision

**Option B.** The server JAR and image serve the built SPA. The SPA stays rendered in the browser and keeps TanStack Router.
The nginx `webapp` image, its Deployment and its configuration are deleted before 1.0.
There is no TanStack Start and no SSR.

The Gateway does not change a path. The server owns each path that it receives:

| Path | Owner |
|---|---|
| `/api/**` | The API. `servers: [{url: /api}]` in the OpenAPI description, so the paths there and in the generated clients stay relative. |
| `/webhooks/**` | Provider webhooks. The webhook role serves them while it is its own Deployment. |
| `/internal/**` | The sandbox gateway, only on its own connector. |
| `/.well-known/**` | From stage 2: the instance metadata, the signing keys and `security.txt` ([RFC 8615](https://www.rfc-editor.org/rfc/rfc8615)). |
| Every other path | The SPA: a hashed asset, or `index.html` for a client route. |

The work lands in four stages. Each stage is one pull request that `git revert` can undo:

1. The server serves the API under `/api` itself. The Gateway `URLRewrite` and `hephaestus.auth.api-base-path` go. Vite's development server sends `/api` to the server.
2. The JAR serves the SPA. CI builds `dist` once, and the JAR packages it precompressed. `SecurityHeaders` in Spring Security is the only header policy. The `webapp` image, its Deployment, its values and its entrypoint go. The browser suite runs against the packaged JAR.
3. A strict CSP: a nonce and `'strict-dynamic'`, after Spring Security 7.2.0 is in the Spring Boot version. Trusted Types in report-only mode first, then enforced [S23].
4. Boot data: `index.html` carries the signed-in account, so the SPA needs no request to learn who is signed in. Public pages get their own Open Graph tags.

## Consequences

- **Self-hosters** run one image and one Deployment fewer. The chart drops the `webapp.*` values. The stage-2 changeset and migration note name the step.
- **The public addresses stay the same.** The sign-in callbacks, `/api/**` and `/.well-known/hephaestus.json` keep their URLs. A Gateway controller needs only `ResponseHeaderModifier`, not `URLRewrite`.
- **One header policy.** The CSP, HSTS, `Referrer-Policy` and the cross-origin policies come only from `SecurityHeaders`. The Gateway adds no header.
- **The browser suite tests the shipped headers,** the fallback and the config record, because it runs against the packaged JAR.
- **The server serves static files.** Hashed assets carry `immutable`, so after the first load the browser reads them from its cache. The steady load on the server is `index.html` and the API.
- **The shell does not load while the server is down.** Without the API it could show only an error. Two server replicas with a rolling update and a disruption budget close that window better.
- **Local development** keeps Vite with hot reload. The browser calls `/api` on the Vite origin, and Vite sends it to the server. A local OAuth app needs the callback `http://localhost:<SERVER_PORT>/api/login/oauth2/code/<id>`.
- **The frontend cannot scale or deploy alone.** Releases already ship in lockstep with one chart version, so this costs nothing today.

## Confirmation

Each stage adds the check that keeps its part true:

- **Stage 1.** The server registers each application route under one constant API path. Only `/webhooks/**`, `/internal/**` and, from stage 2, `/.well-known/**` and the web app's own routes keep their root. The chart tests refuse a `URLRewrite` filter on the route. The OpenAPI description keeps its paths relative to `/api`, so a changed path shows as a diff of the spec and the generated clients.
- **Stage 2.** The browser suite runs against the packaged JAR, not `vite preview`, so it tests the headers, the CSP, the fallback and the config record that ship (AGENTS.md rule 4). It asserts the security headers of the SPA routes. Server integration tests pin the fallback, the asset caching, the 404 for a missing file or an unknown server path, and the discovery and legal documents. The chart tests refuse a `webapp` Deployment and a Gateway header filter.
- **Stage 3.** The header test pins the nonce and `'strict-dynamic'`, and the browser suite fails on a CSP violation.

## Revisit trigger

- A browser measurement shows that the server adds time to first byte for `index.html`.
- Public pages need search ranking. Then add prerendering (option C′), still served by the server.
- An operator needs to scale the frontend separately from the server.

## Sources

The full table with quotes is `lanes/webarch/SOURCES.md` in the 1.0 program notes. These rows decide the outcome:

- [S1] web.dev, *Rendering on the Web*, https://web.dev/articles/rendering-on-the-web. Personalized pages do not suit static rendering, and SSR costs time to first byte and interaction latency. This rules out C.
- [S10] Vite, *Features § Content Security Policy*, https://vite.dev/guide/features#content-security-policy. `html.cspNonce` puts a per-request nonce on every script. This makes stage 3 possible.
- [S11] Vite, *Deploying a Static Site*, https://vite.dev/guide/static-deploy. `vite preview` is not a production server. This is why the browser suite moves to the JAR.
- [S20] OWASP, *Content Security Policy Cheat Sheet*, https://cheatsheetseries.owasp.org/cheatsheets/Content_Security_Policy_Cheat_Sheet.html. A strict CSP is the leading practice.
- [S21] web.dev, *Mitigate XSS with a strict CSP*, https://web.dev/articles/strict-csp. Use a nonce for pages that a server renders.
- [S23] MDN, *require-trusted-types-for*, https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/require-trusted-types-for. Trusted Types are Baseline 2026.
- [S25] Spring Security, *Authorize HTTP Requests § Favor permitAll over ignoring*, https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html. Static resources get their headers only through `permitAll`.
- [S26] Spring Security pull request 18499, https://github.com/spring-projects/spring-security/pull/18499. The nonce-based CSP ships in 7.2.0.
- [S28] nginx, *Security advisories*, https://nginx.org/en/security_advisories.html. The advisory stream that B removes.
- [S40] Spring Boot, `WebProperties`, `spring.web.resources.chain.compressed`. Precompressed resources.
- [S41] Spring Framework, `CacheControl.immutable()`. RFC 8246 caching for hashed assets.
- [S50, S51, S53, S55–S57] Grafana, Argo CD, Immich, Mattermost, Keycloak, Gitea, Authentik, Sentry, Mastodon and GitLab: the backend image serves the frontend. Immich moved from a separate web server to this pattern in v1.88.
