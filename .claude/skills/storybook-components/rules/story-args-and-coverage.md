# What earns a story, and how the Controls stay real

## Which states earn a story

Not "default / all variants / loading / error / edge cases" — that list is uncheckable and half of it
does not apply to any given component. Enumerate what the component can actually do:

- every branch of its own state union, and every value of every `variant`-style enum;
- the two content edges the layout can lose to: the longest realistic string, and the empty collection;
- the 320px reflow viewport **where the component has a horizontal axis** — a row of chips, a toolbar,
  a table. A centred badge does not need one.

A fifth variant story that reaches no new branch adds a Chromatic snapshot and a maintenance edge and
nothing else. One story and no play, for a component with branches, is the actual defect.

**Reflow viewport, house form:** `parameters: { viewport: { defaultViewport: "reflow" } }`, which is
the only form the tree uses for it — set it on the meta to cover a whole file, or on the one story
that needs it. Pair it with `chromatic: { viewports: [320] }` when the snapshot is the point.

**Not a gap:** `webapp/src/components/ui/**` primitives with no story. They are a registry install
that re-vendoring overwrites, so a story per primitive documents upstream's API as if it were ours. Do
not open that as work.

## The Controls must drive the real component

- **A `render` that ignores `args` disables the Controls panel**, which is most of a story's
  documentation value. Prefer `args` alone; when a story needs a wrapper, spread:
  `render: (args) => <Harness {...args} />`. Keep at least one story per file driven by meta args
  (`export const Default: Story = {};`).
- **`autodocs` publishes the `component`'s props.** If the stories render a test harness rather than
  the component, either point `component` at the real component or drop `autodocs` — do not publish the
  harness's props as if they were the API. A file that opts out says why in its meta
  (`webapp/src/components/admin/practice-editor/SortableCatalogTree.stories.tsx`).
- **`layout: "centered"` for an isolated component, `"fullscreen"` for a page-level one.** The default
  padded layout gives a page-level story a margin production does not have, which is how a full-bleed
  header or a sticky footer comes to look right in Storybook and wrong in the app.
- **A controlled component closes its own loop** through `Stateful` / `StatefulPatch`
  (`webapp/src/stories/stateful.tsx`), so the control moves the component *and* the `fn()` spy in `meta.args`
  still fires.

**The swallowed spy.** A hand-rolled stateful wrapper that *overrides* a callback from `args` makes the
`fn()` in `meta.args` unreachable: it can never be asserted and the Actions panel is permanently empty,
while the file looks well instrumented. To find it: for each `fn()` in `meta.args`, grep the file for a
JSX attribute of the same name that is **not** `{...args}`.
`webapp/src/components/admin/practice-editor/OccasionLifecycle.stories.tsx` shows the shape that stays
instrumented: it spreads `{...args}` and patches only the props it holds state for.

## `argTypes` where a prop is explorable, and nowhere else

Add `argTypes` to **correct** what `react-docgen` inferred wrong — a union rendered as a free-text box,
an object arg that should be `control: false` — or to make a genuinely explorable prop explorable. Do
not add them to restate what inference already produced, and never park a default in
`argTypes.defaultValue`: the Controls panel reads `args`, so a default written there renders in the
docs table and nowhere else, leaving the story and its own published documentation disagreeing about
what the component was given.

A component whose one prop is a domain object (`StatusDef`, a `ReactNode`) gains nothing from
`argTypes`; there is no useful control for a `Pick<Wire, …>`. A minority of story files use them, which
is the right shape — this is not a coverage number to raise.

Controls exist because the story is args-driven — *"To use Controls, you need to write your stories
using args"* (storybook.js.org/docs/essentials/controls) — and their types are inferred from the
`component` annotation. `argTypes` are the correction layer, not the source.
