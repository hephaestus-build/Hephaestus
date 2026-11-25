# ADR 0155: One app origin, and a workspace host serves only the public page

**Status:** Accepted
**Date:** 2026-11-25
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md)

**Amends:** [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) rule 10.

**Builds on:** [ADR 0070](0070-1-0-is-a-cutover-release.md) (1.0 is a cutover), [ADR 0126](0126-a-person-has-one-relationship-and-a-personal-space.md) (the personal space), [ADR 0131](0131-a-person-has-one-conversation-in-each-workspace-and-its-answers-stream.md) (the cross-workspace view, item 4)

**Relates to:** [ADR 0052](0052-activity-sorts-by-contributions-and-can-be-public.md) and [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) (the public activity page), [ADR 0091](0091-the-signed-helm-chart-is-the-only-supported-deployment-and-one-host-runs-it-on-k3s.md) (the wildcard certificate), [ADR 0111](0111-the-docs-site-is-a-static-fumadocs-build-on-astro.md) (the theme cookie)

## Context

ADR 0053 gave a workspace host two jobs.
It was the address of the signed-in web app, and it was the public address of a project.
The web app on a workspace host called the API on the apex with credentials.

Each host is its own origin.
A browser cannot change the origin of a page without a new document load.
Thus, every move between two workspaces loaded a new page.
So did every move between a workspace and an apex page, such as instance administration, user settings or product feedback.
People reported these reloads as defects (#5168, #4647).

A person works in several workspaces and has a personal space (ADR 0126).
The proposed cross-workspace conversation view (ADR 0131 item 4, #4923) shows the conversations of all of the person's workspaces on one page.
With ADR 0053, that page lives on the host of one workspace and shows the others.

The hosts gave no isolation to the signed-in app.
They are the same site as the apex, so `SameSite` does not separate them (ADR 0053, Consequences).
SQL tenancy and the server's membership checks separate workspaces ([ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md)), not the host name.

The public job needs no session.
The public activity page reads only public data (ADR 0093).

Felix decided on 2026-11-25 to split the two jobs, as a breaking cutover with no legacy path.

## Decision drivers

- A person moves between workspaces, the personal space and the instance pages without a page load.
- One page can show several workspaces, as the cross-workspace view does.
- The session and every passkey ceremony stay on one host.
- No credentialed cross-origin request and no raw CSRF token endpoint.
- A project keeps its own public address.
- An instance without public hosts needs no wildcard DNS record, no wildcard certificate and no DNS API token.
- 1.0 is a cutover (ADR 0070). No legacy path stays, and a breaking change is allowed.

## Considered options

1. **Keep ADR 0053.** Every workspace switch and every move to an apex page loads a new document.
   Each document load waits for the raw CSRF token before its first API read.
   The credentialed CORS allowlist and the token endpoint stay as attack surface.
2. **Remove the workspace hosts entirely.** It is the simplest: no wildcard record, no certificate and no host code.
   A project loses its public address, and every old workspace-host link stops working.
3. **A session for each host** (ADR 0053 option 4). Each workspace host signs in through the ADR 0048 handoff.
   It adds a session and a sign-out for each host, and a CSRF state and a callback for each host.
   A workspace switch still loads a new document.
4. **One app origin, and the workspace host serves only the signed-out public page (chosen).** The signed-in app moves to the apex, where one origin holds every workspace.
   The host keeps the public job, which needs no cookie.
   Old links into the app keep working through a redirect.

Products with many workspaces in one account put the app on one origin.
Slack's docs show a channel of its web client at a path on `app.slack.com` [1].
Linear gives each workspace a URL "in the style linear.app/example" [2].
Notion shows a public site at a host such as `acme.notion.site`, and inside the app at `app.notion.com/p/acme` [3].

Products that publish user content put it on a separate host with no session.
GitHub Pages serves sites at `<owner>.github.io`, a domain that GitHub moved them to "as a security measure" against cross-domain attacks [4].
Read the Docs serves documentation at `<project>.readthedocs.io`, and its community pages use no cookies, "as all resources are public" [5].

## Decision

1. **One app origin.** The signed-in web app and the API run only on the instance host.
   A workspace is `/w/<slug>`.
   A workspace switch is a client-side route change, with no page load.
   So is a move to instance administration, settings, product feedback or the personal space.
2. **A workspace host serves only the public page.** With public workspace hosts on, `<slug>.<base domain>/` serves the public activity page of a workspace that publishes it (ADR 0052, ADR 0093).
   The host also serves the static files of that page.
   Every other path answers `308` to `<instance>/w/<slug>/<path>?<query>`.
   `/` of a private workspace and of an unknown slug answers the same `308`, so the host reveals no workspace.
   A label that no workspace can have answers `404`.
3. **The public page is signed out and read-only.** The host sets no session cookie and serves no API.
   The page reads the anonymous routes of `PublicActivityController` (ADR 0093 rule 15) on the instance host, without credentials.
   Those routes answer `Access-Control-Allow-Origin: *` and never allow credentials.
   The server writes the slug into the page's config record, so the web app reads no host name.
   The page's sign-in and **Open in app** links go to the instance host.
4. **Public workspace hosts are an instance switch, off by default.** Only that switch needs the wildcard DNS record and the DNS-01 wildcard certificate (ADR 0091).
   The chart value is `publicWorkspaceHosts.enabled`.
   With the switch off, `/w/<slug>` on the instance host is the only address, also for the public page.
5. **Removed in the same release:**
   - The credentialed CORS allowlist and the credentialed branch of `WorkspaceOriginPolicy`.
   - The raw CSRF token endpoint `GET /auth/csrf`. The web app reads the CSRF cookie on its own origin.
   - The web app's `workspaceRewrite` and each `reloadDocument` that a host change caused.
   - The rule that each origin keeps its own **View as user** session.
   - The theme cases for each origin.
   - The extension's mapping of a workspace host to the instance host.
   - The chart value `workspaceSubdomains` and the tests of each removed part.
6. **Rendered links use the path.** Provider comments, Slack messages and email link to `<instance>/w/<slug>/…`.
   With the switch on, a link to the public page uses the workspace host.
   A link that a model writes may point to the instance host (`ModelLinkHosts`).
   It may point to a workspace host only with the switch on.
7. **A slug stays a DNS label.** It has 3 to 51 lowercase ASCII letters, digits and single hyphens.
   It starts and ends with a letter or a digit.
   The reserved names of ADR 0053 stay, so that a workspace host never takes an infrastructure name.
   A renamed workspace keeps its old slug, which redirects to the new one.
   A used slug never names another workspace.
8. **One theme cookie.** The `hephaestus-theme` cookie holds `light`, `dark` or `system`.
   The web app writes it with `Domain=<instance host>`.
   Thus, the app, the public hosts and the docs site under the instance host read the same choice (ADR 0111).
9. **A non-member gets the answer of an unknown workspace.** This keeps ADR 0053's amendment of 2026-11-10.
   A signed-in account that is not a member gets the same `404` and problem body as for an unknown workspace.
   A signed-out request gets `401` before the lookup.
   A member who lacks the role for an action gets `403`.
   Creating a workspace still says that a slug is taken.

## Migration

This is a breaking change for instances that turned on workspace subdomains.

- **Before the upgrade,** replace `workspaceSubdomains` with `publicWorkspaceHosts` in `site.yaml`. The chart schema refuses the old value.
- **Keep** the wildcard DNS record, the DNS API token and the certificate, as long as the switch is on.
- **Members' links** to `<slug>.<host>/<path>` answer `308` and land in the app at `<host>/w/<slug>/<path>`.
  This includes links in email, Slack messages and provider comments from before the upgrade.
- **The root of a public workspace host** now shows the signed-out public page, also to a member. Its **Open in app** link leads into the app.
- **The session does not change.** It was always on the instance host, so a signed-in person stays signed in.
- **A tab left open on a workspace host** runs the old web app, whose credentialed API calls now fail. Its next load lands on the public page or the redirect.
- **Browser storage of a workspace host does not move.** It holds conveniences such as tour and Get started records, so a person can see one of these again once.
- Instances that never turned on workspace subdomains change nothing.

The changeset of the implementation states these steps in the operator's voice, marked breaking in its body, with a migration guide entry.

## Consequences

- Good: workspace switches and moves to instance pages are route changes, with no page load.
- Good: a cold load reads no CSRF token before its first API read.
  Thus, every cold load has one less serial round trip.
- Good: no credentialed cross-origin request, no raw CSRF token endpoint, and passkeys on one origin.
- Good: a project keeps its public address.
  A script on that page cannot act for a person, because the host has no session.
- Good: a custom domain for a project's public page becomes possible later, because the page needs no cookie. This ADR does not build it.
- Bad: a public workspace has two addresses for its public page: the host and `/w/<slug>`.
- Bad: a private workspace has no host of its own.
- Bad: a member who opens the root of a public workspace host sees the public page, not the app.
- Bad: browser storage on a workspace host is lost once, at the upgrade.

## Confirmation

These tests already confirm the slug rules of decision 7:

- `WorkspaceSubdomainDatabaseTest` rejects a used slug for another workspace, also after a rename or a deletion.
- `WorkspaceSlugRenameIntegrationTest` redirects an old slug to the current one, and rejects a slug in another workspace's history.

Not enforced. The two cutover pull requests add a test for each other decision, and each test fails when the code breaks it:

- A server test of the host guard, with the switch on:
  - `/` of a public workspace serves the public page.
  - `/` of a private workspace and of an unknown slug answer the same `308`.
  - Every other path answers `308` to `<instance>/w/<slug>/<path>?<query>`.
  - A reserved or invalid label answers `404`.
  - No response on a workspace host sets a cookie other than the theme cookie.
- A server test of CORS. No response allows credentials for an origin other than the instance host. The anonymous public routes answer `*` without credentials.
- A server test of `GET /auth/csrf`. It answers `404`.
- A web app route test. A workspace switch and a move to `/admin` commit in the same document.
- The tests of rendered links assert `/w/<slug>`, and the workspace host for the public page only with the switch on.

Each cutover pull request names its tests here when it adds them.

## Revisit trigger

- A project asks for a custom domain for its public page.
- A workspace needs its own data region or its own deployment. That is a separate instance.

## Sources

1. Slack, *Formatting message text*, § Linking to channels: <https://docs.slack.dev/messaging/formatting-message-text/>. It shows the web client URL `https://app.slack.com/client/E123ABC456/C123ABC456`.
2. Linear, *Concepts*, § Workspace, and *Workspaces*, § Multiple workspaces: <https://linear.app/docs/conceptual-model>, <https://linear.app/docs/workspaces>.
   "Workspaces each have a unique URL in the style linear.app/example."
   One account switches between its workspaces with **Switch workspace**, or with `O` then `W`.
3. Notion, *Manage your Notion Sites*, § Create a new notion.site domain: <https://www.notion.com/help/manage-your-notion-sites>.
   The domain shows "at the start of any public page URLs, such as `acme.notion.site`".
   "Internally, you will still see the URL as `app.notion.com/p/acme`."
4. GitHub, *What is GitHub Pages?*, § Types of GitHub Pages sites: <https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages>, with sites at `<owner>.github.io`.
   GitHub blog, *New GitHub Pages domain: github.io* (2013): <https://github.blog/news-insights/the-library/new-github-pages-domain-github-io/>.
   "This is a security measure aimed at removing potential vectors for cross domain attacks."
5. Read the Docs, *Custom domains*, § Default subdomains, and *Security considerations for documentation pages*, § Cookies: <https://docs.readthedocs.com/platform/stable/custom-domains.html>, <https://docs.readthedocs.com/platform/stable/security-implications.html>.
   Documentation is served at `<project name>.readthedocs.io`.
   Its community documentation pages use no cookies, because all of their resources are public.

## Already priced and declined

- [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md#considered-options) option 1, paths only. Declined again, as option 2 above: a project keeps its public address.
- [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md#considered-options) option 2, a host for the public page only. ADR 0053 declined it because a workspace then has two addresses and a private workspace gets none. This ADR now chooses it, and accepts both costs for the drivers above.
- [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md#considered-options) option 3, a domain cookie for every subdomain. Still declined. The auth cookies stay host-only on the instance host.
- [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md#considered-options) option 4, a sign-in broker for each host. Still declined, as option 3 above.
- [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md#considered-options) option 5, a custom domain for each workspace. Not built now. Decision 3 makes it possible later.
- [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md#considered-options) TLS options 1 and 2, a CDN proxy and one certificate for each host. Still declined. The wildcard certificate through DNS-01 stays for public workspace hosts.
