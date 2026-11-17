# Hephaestus Documentation

[![Documentation Status](https://github.com/hephaestus-build/Hephaestus/actions/workflows/cd-docs.yml/badge.svg)](https://github.com/hephaestus-build/Hephaestus/actions/workflows/cd-docs.yml)

**Live Site:** [https://docs.hephaestus.build/](https://docs.hephaestus.build/)

The site is [Fumadocs](https://fumadocs.dev/) on [Astro](https://astro.build/), built to static HTML ([ADR 0111](decisions/0111-the-docs-site-is-a-static-fumadocs-build-on-astro.md)).
`astro.config.ts` configures the build and the Markdown plugins, `src/content.config.ts` the content collections, and `src/` holds the pages, layouts and components.
`vite.config.ts` holds only the lint configuration for `vp lint`.

The content of a page is static HTML. Only the layout (sidebar, table of contents, search, theme and page actions) is a React island that runs in the browser.
The island imports the sidebar's page tree from one module (`virtual:docs-page-tree` in `astro.config.ts`), so the browser loads the tree once for all pages.

The content is split into three guides:

- **User guide** (`user/`): end-user workflows, such as practice feedback, activity, Hephaestus, and workspace management.
- **Contributor guide** (`contributor/`): engineering guides, the generated ERD, and local development setup.
- **Admin guide** (`admin/`): install, integrations, and production operations.

Those three directories are the whole site.
Each one is a root folder with its own sidebar in its `meta.json`.
Every page is an `.mdx` file: the Fumadocs integration compiles only `.mdx`.
A file or a folder whose name starts with `_` is a partial: a page inlines it with `::include[./_file.mdx]`, and it is no page.
The callouts, the guard against an unparsed callout, the text of unused directives, and Mermaid are plugins of the Sätteri compiler (`src/lib/plugins.ts`).
`src/lib/plugins.test.ts` tests each one on a fixture page.

## URLs

The URL of a page is its path in its guide: `admin/upgrade.mdx` is `/admin/upgrade`, and `admin/dsms/index.mdx` is `/admin/dsms`.
The build writes `admin/upgrade.html`, which GitHub Pages serves at `/admin/upgrade`, with no trailing slash.
The site has no redirects.
A rename changes a URL, so rename a page only in a change to the structure of a guide.

## Versions

The site serves two versions, each built from its own Git ref:

| Path | Ref | Banner |
| --- | --- | --- |
| `/` | The latest release tag | The pre-1.0 notice, until the first stable release |
| `/next/` | `main` | "Unreleased: these docs describe the next release" |

`scripts/docs-release.ts` picks the release tag.
Before the first stable 1.0.0, it picks the newest release, a release candidate included.
From 1.0.0 on, it picks the newest stable release.
The version switcher in the navbar links the two sites.
Search engines index only the site root.

`cd-docs.yml` builds both refs on a push to `main` that changes `docs/**`.
It also builds them after a run of the release workflow that published a release, so a release republishes the site root with its own docs.
The repository holds one copy of the docs: the release docs come from the tag, not from a copied tree.

`DOCS_CHANNEL` (`latest` or `next`) and `DOCS_RELEASE` (the tag) set the banner and the switcher.
`DOCS_BASE_URL` (`/` or `/next/`) sets the base path.
A local build or a pull-request preview shows `main`, so it is `next`.

## Markdown for AI assistants

`llms()` from `fumadocs-core` writes these files at build time (`src/lib/llms.ts`):

- `/llms.txt` is the index of every page, grouped by guide.
- `/llms-full.txt` holds every page.
- `/<page>.md` is the Markdown twin of each page.

A twin is the processed Markdown of the page: an `::include` is inlined, and a component stays as JSX.
Mermaid draws its diagrams in the browser, so a twin holds the diagram source.

The **Copy Markdown** button on each page copies the twin, and its menu opens the twin in an assistant.
The dev server serves them too.

## Search

[Pagefind](https://pagefind.app/) indexes the built pages after the build (`pagefind --site dist`).
The browser loads only the parts of the index that a query needs, and no hosted service runs.
The index exists only in a build, not in `docs:dev`.

## Theme

The site imports `webapp/src/styles/theme-tokens.css` with the Fumadocs shadcn preset, so the docs and the web app share one palette.
The theme is the `hephaestus-theme` cookie of ADR 0053: a head script reads it before paint, and a change of theme writes it back (`src/components/theme-cookie.tsx`).

## Diagrams

Mermaid, validated by `vp run gate:diagrams` — which fails a diagram with no `accTitle` and
`accDescr`, because the picture is the only copy of that information for a sighted reader.

Three shapes, so a reader recognises what they are looking at before reading it:

| Shape | Use | Node ids |
|---|---|---|
| `flowchart LR` | something moves through owners or boundaries | PascalCase, labelled with what crosses the arrow |
| `flowchart TB` | stages, and the distinct way each one can end | PascalCase |
| `sequenceDiagram` | one exchange across processes | participant names as deployed |

No emoji, no single-letter ids, and no `graph` — it is a deprecated alias for `flowchart`.

## What is deliberately *not* published

`decisions/`, `auth-architecture.md` and `auth-glossary.md` are repo-only reference
material — no plugin serves them, and they are read on GitHub. See
[`decisions/README.md`](decisions/README.md) for why, and for the rule that a published page links
into them by absolute GitHub URL rather than a relative path.

## Prerequisites

The toolchain the [local development guide](contributor/local-development.mdx#prerequisites)
installs. The docs site is a workspace package, so a root `vp install` covers it.

## Scripts

Run from the repo root:

```bash
vp run docs:dev     # Development server at http://localhost:3000/
vp run docs:build   # Production build; a broken link or anchor fails it (scripts/check-docs-links.ts)
vp run docs:serve   # Preview the production build
vp run docs:lint    # TypeScript check and markdownlint
```

Every docs script starts Astro through `scripts/docs-astro.ts`, which turns off Astro's anonymous telemetry.
`docs:lint` runs `astro check`, which type-checks the `.astro` files and writes the content types (`.astro/`, ignored by Git) that the lint's type check reads.

`docs:lint` is part of `vp run check`; `docs:build` is part of `vp run verify`, and the deployment
workflow runs the same package script.

## Deployment

`cd-docs.yml` deploys both [versions](#versions) to GitHub Pages.
A pull request gets no hosted docs preview. The tooling leg of CI builds the docs at `/`, `cd-docs.yml` builds them at `/next/`, and `vp run docs:dev` serves them locally.
