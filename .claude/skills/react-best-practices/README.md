# react-best-practices

A vendored snapshot of Vercel Engineering's React performance pack (MIT, see `SKILL.md` frontmatter).
There is no build step here.
Upstream ships `AGENTS.md` as the compiled form of `rules/*.md`.
An in-place edit to either makes the next re-vendor a conflict instead of an overwrite.

- `SKILL.md` — what the loader reads. This repository maintains its applicability section.
  Read that section first.
  Much of the pack is Next.js/RSC-only and does not apply to this SPA.
- `rules/*.md` — one rule each. `_sections.md` carries the section order and impact levels.
- `AGENTS.md` — every rule expanded into one document.
