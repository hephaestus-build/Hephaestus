# ADR 0111: The docs site is a static Fumadocs build on Astro

**Status:** Accepted
**Date:** 2026-11-15
**Authors:** Felix T.J. Dietrich
**Relates to:** [0040](0040-vite-plus-is-the-command-surface.md), [0053](0053-workspace-addresses-are-a-presentation-origin.md)

## Context

The docs site was Docusaurus 3.10.2 with three guides on GitHub Pages at `docs.hephaestus.build`.
The root serves the latest release tag, and `/next/` serves `main` (`docs/README.md` § Versions).
The maintainers want docs that read and look like the best open-source docs, with Langfuse as the reference.

The real constraints are these:

- GitHub Pages serves static files only. It runs no server and cannot send an HTTP redirect.
- Every published page URL and every heading ID that the web app links to must stay.
- The docs gates must stay, and the docs must look like the web app.
- A page must not load more JavaScript and CSS before it renders than the Docusaurus page loads: 188 KB (gzip).

## Decision drivers

- The page weight gate above.
- A static build that GitHub Pages serves, with the current URLs.
- Static search with no hosted service, and a Markdown twin of each page.
- One token file for the web app and the docs.
- No permanent workaround in the platform.

## Considered options

1. **Fumadocs on Astro.** The page content is static HTML. Only the layout is a React island. It passes the gate.
2. **Fumadocs on TanStack Start.** It uses the router and Vite of the web app. Every page hydrates in full, so it fails the gate.
3. **Fumadocs on Next.js with static export.** Langfuse runs this. It also hydrates every page and adds a second application framework.
4. **Fumadocs on React Router 8 or on Waku.** Both hydrate every page. Waku is a release candidate.
5. **Fumapress.** It started in October 2025, changed its API twice, and depends on a Waku release candidate.
6. **Astro Starlight.** It has the lowest page weight, but not the Fumadocs layout. Its theme copies the tokens.
7. **Docusaurus with a redesign.** The tokens stay copied into Infima, and a polished theme needs swizzled components.

Prototypes of options 1 and 2 were measured against Docusaurus on one host. The cutover pull request gives the runs.

| Measure | Docusaurus | TanStack Start | Astro |
| --- | --- | --- | --- |
| JavaScript and CSS before render, docs page (gzip) | 188 KB | 243 KB | 173 KB |
| JavaScript and CSS before render, home page (gzip) | 188 KB | 224 KB | 161 KB |
| HTML of a docs page (gzip) | 8 KB | 10 KB | 22 KB, before the shared page tree |
| First search download (gzip) | 2.6 MB | 160 KB | 160 KB |
| Lighthouse accessibility | 96 to 97 | 100 | 100 |
| Pages and Markdown twins kept | 145 and 142 | all | all |

## Decision

We choose option 1.

- The site is `fumadocs-core` and `fumadocs-ui` with `@fumadocs/astro` on Astro, built to static HTML. No server runs in production.
- The build writes `<path>.html` for each page and a root `404.html`. The site has no redirects.
- Every page is an `.mdx` file in `docs/user`, `docs/admin` or `docs/contributor`. Each guide is a content collection with its own sidebar in `meta.json`.
- A partial starts with `_`. The collection schema lets a partial load without a title, and `getCollection()` leaves it out of the site.
- The callouts, the guard against an unparsed callout and the text of unused directives are Sätteri plugins. A test runs each one on a fixture page.
- The theme imports `webapp/src/styles/theme-tokens.css` with the Fumadocs shadcn preset. The `hephaestus-theme` cookie of ADR 0053 stays.
- Pagefind indexes the built HTML. `llms()` from `fumadocs-core` writes `llms.txt`, `llms-full.txt` and a `.md` twin of each page.
- The islands import the sidebar's page tree from one module, so the browser loads the tree once.
- Each docs script starts Astro through `scripts/docs-astro.ts`, which turns off Astro's telemetry.
- `@fumadocs/astro` and the Sätteri packages have exact pins. Renovate moves each one in its own pull request.

## Consequences

- Good: a docs page loads less JavaScript and CSS than the Docusaurus page.
- Good: the first search downloads a sixteenth of the old search index.
- Good: the docs and the web app share one token file and one component system.
- Bad: Astro builds the site with its own Vite, beside the Vite+ of the repository.
- Bad: `@fumadocs/astro` is a 0.x package. A minor release can change the compiler that the plugins run on.
- Bad: the `.md` pages became `.mdx`, and every reference to them changed in the same pull request.
- Bad: the four client redirects of the Docusaurus site are gone. The changeset names their URLs.
- Bad: the `cn` helper of Fumadocs costs 9.6 KB (gzip) on each page, mostly for `tailwind-merge`. We report it upstream and do not patch it.

## Confirmation

- `vp run docs:build` fails on a build error, a broken link or a broken anchor (`scripts/check-docs-links.ts`).
- `check-doc-targets` checks the web app's docs links on the built HTML.
- `node --test docs/src/lib/plugins.test.ts` fails when the compiler changes what a plugin does.
- `vp run gate:docs-lint` runs `astro check`, the lint with its type check, and markdownlint.

## Revisit trigger

- `@fumadocs/astro` has no release for six months, or it drops static builds.
- A docs page loads more JavaScript and CSS than 188 KB (gzip).
- The docs move to a host that can send HTTP redirects.

## Sources

- [Fumadocs on Astro](https://fumadocs.dev/docs/astro/manual-installation): the content renders as static HTML inside a layout island.
- [Astro content collections](https://docs.astro.build/en/guides/content-collections/): the collection schema and the filter of `getCollection()`.
- [Langfuse docs `package.json`](https://github.com/langfuse/langfuse-docs/blob/main/package.json): Fumadocs 16 on Next.js 16, with a static export.

## Update — 2026-11-21: no upstream report for the `cn` helper

The maintainers decided to make no upstream report for the `cn` helper.
The helper costs 9.6 KB (gzip) on each page, mostly for `tailwind-merge`. We accept that cost and do not patch the helper.
With it, a docs page loads 173 KB (gzip), below the 188 KB gate (§ Considered options). The revisit trigger for that gate stays.
This replaces the `cn` consequence in § Consequences, which said that we report the helper upstream.
