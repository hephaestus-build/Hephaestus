---
name: web-design-guidelines
description: Review UI code for Web Interface Guidelines compliance. Use when asked to "review my UI", "check accessibility", "audit design", "review UX", or "check my site against best practices".
metadata:
  author: vercel
  version: "1.0.0"
  argument-hint: <file-or-pattern>
---

# Web Interface Guidelines

Follow the [writing standard](../../../docs/contributor/simplified-technical-english.md) for all prose.


The rules are not in this file — fetch them, then review the named files against what you fetched:

```
https://raw.githubusercontent.com/vercel-labs/web-interface-guidelines/main/command.md
```

That document carries the full rule set (accessibility, focus, forms, animation, typography, images,
performance, navigation, touch, safe areas, theming, i18n, hydration, copy) **and** its own output
format. Follow the format it states; do not impose one from here.

If no file or pattern was given, ask which files to review rather than guessing at the tree.

## Reading it against this repo

Two rule groups will misfire here unless you check first:

- **Hydration safety** assumes SSR. The webapp is a client-rendered Vite SPA, so hydration-mismatch
  rules have no subject.
- **Accessibility** overlaps the Storybook a11y suite, which already runs axe at `test: "error"` on
  every story. A finding that axe would have caught is a bug in the story coverage, not in the
  component — check `/storybook-components` § a11y before filing it as a UI defect.
