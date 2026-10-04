# composition-patterns

A vendored snapshot of Vercel's React composition-patterns pack (MIT, see `SKILL.md` frontmatter).
There is no build step here.
Upstream ships `AGENTS.md` as the compiled form of `rules/*.md`.
An in-place edit to either makes the next re-vendor a conflict instead of an overwrite.

- `SKILL.md` — what the loader reads, and the only file this repo maintains.
- `rules/*.md` — one rule each. `_sections.md` carries the section order and impact levels.
  If a summary disagrees, that file is the authority.
- `AGENTS.md` — every rule expanded into one document.

`/storybook-components` states what these patterns cost in this repo — Base UI `render=` slots,
Storybook Controls, the two-call-site test. Read it alongside, not instead.
