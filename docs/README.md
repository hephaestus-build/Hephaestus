# Hephaestus Documentation

[![Documentation Status](https://github.com/hephaestus-build/Hephaestus/actions/workflows/cd-docs.yml/badge.svg)](https://github.com/hephaestus-build/Hephaestus/actions/workflows/cd-docs.yml)

**Live Site:** [https://docs.hephaestus.build/](https://docs.hephaestus.build/)

This site is built with [Docusaurus 3](https://docusaurus.io/); `docusaurus.config.ts` is its configuration. The content is split into three guides:

- **User Guide** (`user/`) – End-user workflows (mentor sessions, leaderboard, workspace management)
- **Contributor Guide** (`contributor/`) – Engineering guides, the generated ERD, and local development setup
- **Admin Guide** (`admin/`) – Install, integrations, and production operations

Those three directories are the whole site: each is one `@docusaurus/plugin-content-docs` instance in
`docusaurus.config.ts`, and every page in them must be reachable from its sidebar.

## Diagrams

Mermaid, validated by `vp run gate:diagrams` — which fails a diagram with no `accTitle` and
`accDescr`, because the picture is the only copy of that information for a sighted reader.

Three shapes, so a reader recognises what they are looking at before reading it:

| Shape | Use | Node ids |
|---|---|---|
| `flowchart LR` | something moves through owners or doors | PascalCase, labelled with what crosses the arrow |
| `flowchart TB` | stages, and the distinct way each one can end | PascalCase |
| `sequenceDiagram` | one exchange across processes | participant names as deployed |

No emoji, no single-letter ids, and no `graph` — it is a deprecated alias for `flowchart`.

## What is deliberately *not* published

`decisions/`, `runbooks/`, `auth-architecture.md` and `auth-glossary.md` are repo-only reference
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
vp run docs:build   # Production build; a broken link, anchor or Markdown link fails it
vp run docs:serve   # Preview the production build
vp run docs:lint    # TypeScript check and markdownlint
```

`docs:lint` is part of `vp run check`; `docs:build` is part of `vp run verify`, and the deployment
workflow runs the same package script.

## Deployment

`cd-docs.yml` deploys a push to `main` to GitHub Pages and a pull request to a Surge.sh preview with
a URL comment; `cd-docs-teardown.yml` removes the preview when the pull request closes.
