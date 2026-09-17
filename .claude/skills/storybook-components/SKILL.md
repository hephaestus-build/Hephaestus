---
name: storybook-components
description: >
  Component API and Storybook conventions for the Hephaestus webapp. Use when writing or
  changing a component under `webapp/src/components/**`, writing or reviewing a `*.stories.tsx`,
  designing a component's props, or grading a webapp diff in review. Covers props/state shape,
  Base UI `render=` slots, the vocabulary registries, story titles, args and Controls,
  play-function assertions, and the accessibility posture. Ships a grading rubric.
metadata:
  source: internal
  version: "1.0.0"
---

# Storybook + component API

Load the one file that answers your question.

| File | The question it answers |
|---|---|
| `rules/props-state-shape.md` | What shape should this prop be — object or scalars, union or flags, controlled or not — and does it deserve to exist? |
| `rules/composition-and-slots.md` | Should this be `children`, a prop, a slot, context, or a compound API — and what does a slotted element owe the primitive? |
| `rules/vocabulary-registries.md` | Where do a status's label, icon and colour come from, and when does a badge render nothing? |
| `rules/story-titles.md` | Where does this story land in the sidebar? |
| `rules/story-args-and-coverage.md` | Which states earn a story, and how do the Controls stay wired to the real component? |
| `rules/play-assertions.md` | Can this play function fail, and how do I assert against a portal or a just-opened overlay? |
| `rules/a11y.md` | What does axe not catch here, and what must a component name for itself? |
| `traps.md` | Something passed that should not have. Read this before debugging a green-but-wrong story. |
| `RUBRIC.md` | Grading a diff across nine dimensions, D→A+, with the anti-criteria that look like quality and are not. |

## Already enforced — do not re-litigate, and do not restate in prose

These fail `vp run check`. Treat a violation as a build error, not a style opinion, and do not
write a guideline that repeats one.

The house rules are registered in `webapp/tools/oxlint/index.ts` — read it rather than trusting a
list, since a rule can be added without this file changing. Those that reach a story file:

- `hephaestus/typed-story-meta` — a `meta` naming a `component` must be `satisfies Meta<typeof X>`
  (not annotated `: Meta<typeof X>`, which widens `typeof meta`) and its stories
  `StoryObj<typeof meta>`, so a story that omits a required prop is a type error; a gallery meta
  naming no component may be bare `Meta`.
- `hephaestus/play-must-assert` — a `play` that never reaches an assertion. It reads a `getBy*` used
  as a click target as an assertion, so it holds only the floor; whether the play checks the
  **outcome** is still a review question.
- `hephaestus/no-story-a11y-override` — `parameters.a11y` or `globals.a11y` on a meta or a story.
  Either one alone takes the component out of the accessibility suite while it still reports green.
- `hephaestus/no-redundant-in-the-document` — `expect(getBy…).toBeInTheDocument()`. A bare
  `await expect(getBy…)` is `vitest/valid-expect`, which catches it for every subject.
- `hephaestus/no-within-canvas-element` — `within(canvasElement)` when the play function was handed
  `canvas`.
- `hephaestus/prefer-auto-story-title` — any `title` on a meta; the sidebar mirrors the source tree.
- `hephaestus/no-nondeterministic-render` — `new Date()`, `Date.now()` or `Math.random()` at module
  scope or in render; a story takes its clock from `@/stories/story-clock`.
- `hephaestus/svg-needs-accessible-name` — an inline `<svg>` with neither `aria-hidden` nor a name.

The ones that only make sense in a story file are scoped to `**/*.stories.tsx` in the `overrides`
block of `webapp/.oxlintrc.json` rather than named in its top-level `rules`. A house rule missing
from both is simply off, and nothing reports that.

Beyond oxlint:

- `scripts/check-story-prose.ts` (`gate:stories`) — `<p>` in a comment Storybook publishes, and two
  story files that Storybook would file under one sidebar node.
- `scripts/check-presentational-components.ts` (`gate:components`) — a component importing the
  query layer, and a story installing MSW handlers. Its allowlist is shrink-only.
- `webapp/.storybook/preview.tsx` sets `a11y: { test: "error" }` project-wide. Adding a per-story
  override is a finding, not a fix.

## Not here

`/composition-patterns` owns the React 19 API shape (`react19-no-forwardref`), render props, and
the generic compound-component pattern. This skill states only what those cost *here*.
