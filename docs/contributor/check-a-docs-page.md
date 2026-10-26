---
title: Check a docs page
description: Run the prose, page, and build checks on a changed docs page before you open a pull request.
page_type: how-to
---

Check each changed docs page before you open a pull request.
The [documentation guide](./documentation-guide.md) gives the rules that these checks hold.

## Before you start

Install the repository dependencies, as [Local verification](./local-verification.mdx#dependency-installation) describes.

## Check the page

1. Run `vp run lint:prose <path>` for each changed page. Fix each error.
2. Run `vp run gate:docs-pages`.
3. Run `vp run docs:lint`.
4. Do each step of a how-to page or a tutorial on a running instance. Write the result that you saw, not the result that you expect.
5. Run `vp run docs:dev`. Read the page in the light and the dark color scheme.
6. If you changed links, the sidebar, or a component, run `vp run docs:build`. A broken link stops the build.

## Check that it worked

- Each command ends with no error.
- The page reads correctly in both color schemes.
