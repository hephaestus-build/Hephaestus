---
title: Add a user-guide screenshot
description: Capture a screen of the web app in the light and the dark color scheme, and show it on a user page.
page_type: how-to
---

Add a screenshot when a user page must show the reader a screen of the web app.
The [documentation guide](./documentation-guide.md#screenshots-of-the-web-app) gives the rules for the images.

## Before you start

Install the repository dependencies, as [Local verification](./local-verification.mdx#dependency-installation) describes.

## Add the screenshot

1. Add the screen to the list in `webapp/e2e/screenshots/user-guide.shot.ts`.
2. Add the data for the screen to `webapp/e2e/screenshots/workspace.ts`. Use invented names. Use practice names from the shipped catalog.
3. Run `vp run generate:docs-screenshots`.
4. Look at the light and the dark image.
5. Add the `Screenshot` component to the page with its `src`. Give it an `alt` text that says what the image shows, in at most 155 characters.

## Check that it worked

- `vp run generate:docs-screenshots` ends with no failed screen.
- `vp run gate:docs-pages` ends with no error.
- `vp run docs:dev` shows the image of each color scheme on the page.
