# ADR 0109: The docs site is a static Fumadocs build on TanStack Start

**Status:** Proposed
**Date:** 2026-11-14
**Authors:** Felix T.J. Dietrich
**Relates to:** [0040](0040-vite-plus-is-the-command-surface.md), [0053](0053-workspace-addresses-are-a-presentation-origin.md)

## Context

The docs site is Docusaurus 3.10.2 with 200 pages in three guides.
GitHub Pages serves it at `docs.hephaestus.build`.
The root serves the latest release tag, and `/next/` serves `main` (`docs/README.md` § Versions).
The maintainers want docs that read and look like the best open-source docs, with Langfuse as the reference.

The real constraints are these:

- GitHub Pages serves static files only. It runs no server and cannot send an HTTP redirect.
- Every published URL and every heading ID that the web app links to (`webapp/src/lib/doc-targets.ts`) must stay.
- The docs gates must stay: `gate:prose`, `gate:docs-pages`, `gate:diagrams`, `check-doc-targets`, and the Markdown twins for AI assistants.
- The docs must look like the product. The web app is React 19, Tailwind 4, shadcn tokens, Base UI and TanStack Router on Vite+.

Today the theme is Infima, so `gate:docs-tokens` pins copies of the app tokens.
The first page load downloads 184 KB of JavaScript and CSS (gzip).
The first search downloads a 9.5 MB index (2.6 MB gzip).

## Decision drivers

- One stack and one token file for the product and its docs.
- A static build that GitHub Pages serves, with the current URLs and no new redirects.
- Static search with no hosted service.
- `llms.txt` and a Markdown twin for each page.
- A framework feature for each need, not a custom plugin.
- A maintained project with a stable API.

## Considered options

1. **Fumadocs on TanStack Start, prerendered.** It uses the same React, Tailwind, shadcn tokens, Base UI, router and Vite as the web app. Fumadocs has one main maintainer and releases often. Its static output for TanStack Start is younger than its Next.js export.
2. **Fumadocs on Next.js with static export.** Langfuse runs this, and it is the most proven Fumadocs path. It adds Next.js as a second application framework and toolchain beside Vite+ (ADR 0040). It gives nothing that option 1 does not give.
3. **Fumadocs on React Router 8, prerendered.** React Router prerenders static pages well. It adds a second router beside TanStack Router. It is the fallback if option 1 fails the prototype.
4. **Fumadocs on Waku.** Waku is at `1.0.0-rc.5`. We do not build on a release candidate.
5. **Fumapress.** It is real and MIT-licensed, from the Fumadocs author. It started in October 2025. Its API changed for v1 in July 2026 and for v2 in October 2026. It depends on a Waku release candidate. Its zero-configuration build hides the steps where our gates connect.
6. **Astro Starlight 0.43.** A team maintains it. It has Pagefind search, the same `:::` callout syntax, and the lowest page weight. It is still a 0.x release with changes that break in minor releases. Its theme uses its own `--sl-*` variables, so the tokens stay copied. Our components become Astro components or React islands.
7. **Docusaurus 3.10 with a redesign.** This is the cheapest option, and every gate works today. Meta maintains it. The tokens stay copied into Infima. Polish needs swizzled components that break on upgrades, and Docusaurus 4 is still a canary.

## Decision

We choose option 1.

- The site is `fumadocs-core`, `fumadocs-ui` and `fumadocs-mdx` on TanStack Start. The build prerenders every page to static HTML. No server runs in production.
- The build writes `<path>.html` for each page (`autoSubfolderIndex: false`) and a root `404.html`. GitHub Pages serves each current URL without a trailing slash.
- The migration keeps every path of the current sitemap and every heading ID. It adds no redirect.
- The pages stay in `docs/user`, `docs/admin` and `docs/contributor`, with the same front matter. Each guide is a Fumadocs root folder with its own sidebar. A `meta.json` file replaces each `sidebars.<guide>.ts` file. A nested category becomes a separator, because a folder would change the URLs.
- `:::note`, `:::tip` and `:::caution` stay, through `remark-directive` and `remarkDirectiveAdmonition`. Other text that looks like a directive, such as `role:admin`, stays text. A script changes explicit heading IDs from `{/*#id*/}` to `[#id]`.
- A `.md` page is plain Markdown, and a page with a component is `.mdx`. A page inlines a partial with `::include`, so its headings reach the table of contents and the Markdown twin.
- The docs theme imports `webapp/src/styles/theme-tokens.css` with the Fumadocs shadcn preset. No token is copied. The change deletes `gate:docs-tokens`. The `hephaestus-theme` cookie of ADR 0053 stays.
- Search runs in the browser with no hosted service. The first search downloads at most 300 KB (gzip). The built-in static index of Fumadocs is 4.0 MB (gzip) for the current pages. Thus, Pagefind indexes the built HTML.
- `llms()` from `fumadocs-core` writes `/llms.txt`, `/llms-full.txt`, one `llms-full.txt` for each guide, and a `.md` twin for each page. The page actions stay.
- The version model stays: one build for each Git ref, with the base path from the environment. Each tag builds with its own toolchain. Thus, the root changes to Fumadocs with the first release that contains this change.
- The generated content stays: the configuration reference, the ERD diagrams, the user-guide screenshots and the system diagrams. Their image URLs do not change.
- One pull request deletes Docusaurus, its plugins, its configuration and its swizzled components.
- An OpenAPI reference is not part of this decision. `fumadocs-openapi` can render `server/openapi.yaml` when an ADR gives the API a public reader.

## Consequences

- Good: the docs and the web app share one token file and one component system. They look like one product.
- Good: the site uses the Fumadocs layouts that Langfuse uses, with search, page actions and Markdown twins built in.
- Good: the docs build runs on Vite under `vp`, like the web app.
- Bad: Fumadocs has one main maintainer. Exact version pins and the ejectable UI (`@fumadocs/cli add`) limit the risk.
- Bad: Fumadocs releases often. Each upgrade needs a docs build and a look at the pages.
- Bad: TanStack Start prerendering is younger than Docusaurus. The prototype must prove the output before the migration.
- Bad: the change removes the four client redirects of `docusaurus.config.ts`. The no-legacy baseline requires that before 1.0 (`docs/contributor/local-verification.mdx`). The changeset names the four URLs. An old link to one of them gets the 404 page, which offers search and the other version.

## Confirmation

- `vp run docs:build` fails on a prerender error, a link to a missing page, or a broken anchor. `scripts/check-docs-links.ts` checks the links of the built HTML and replaces `onBrokenLinks` and `onBrokenAnchors`.
- `check-doc-targets` runs on the built HTML, as today.
- `gate:docs-pages`, `gate:prose` and `gate:diagrams` run unchanged on the same files.
- The migration pull request attaches a path report. It compares the paths and heading IDs of the old and the new build. It must show no missing path.

## Revisit trigger

- Fumadocs has no release for six months, or its maintainer stops.
- TanStack Start removes static prerendering.
- The docs move to a host that can send HTTP redirects.
- The prototype fails its exit criteria. Then we try option 3, and after it option 6.

## Sources

- [Fumadocs: static build on TanStack Start](https://fumadocs.dev/docs/tanstack-start/deploying/static): no search server, static search index, prerender configuration.
- [TanStack Start: static prerendering](https://tanstack.com/start/latest/docs/framework/react/guide/static-prerendering): `autoSubfolderIndex` writes `/page.html` instead of `/page/index.html`.
- [Langfuse docs `AGENTS.md`](https://raw.githubusercontent.com/langfuse/langfuse-docs/main/.agents/AGENTS.md) and [`package.json`](https://github.com/langfuse/langfuse-docs/blob/main/package.json): Fumadocs 16 on Next.js 16, static export, and redirects through Cloudflare.
