# Grading instrument — Storybook + component API

Scope: `webapp/src/components/**` and their colocated `*.stories.tsx`. **Grade a diff, not the repo.**

**Calibration contract.** A file that renders correctly, is formatted, passes `vp run check`, has a
story per variant and a green a11y panel scores **C**. C is the floor for competent work, not a
criticism. B costs deliberate design. A costs a rejected alternative written down. A+ is rare by
construction — at most one dimension per PR should reach it.

Every dimension below carries the command that measures it. **Run the command; do not carry a number
in your head.** What the tree does is calibration for what "normal" looks like, never a target — a
ratio is evidence about a band, not a defect count.

---

## Dimension 1 — Can the props type express a state the product cannot?

- **D** — Two or more booleans encode one mode (`n` booleans, `2^n` representable, `<2^n` real), or
  parallel `isLoading` + `data` + `error` on the component that owns the swapping region.
- **C** — No impossible pair, but a prop is derivable from another prop the component already has, or a
  value prop is `on`-prefixed and reads as a handler.
- **B** — Mutually exclusive states are one discriminated union or one enum; every branch a query can
  reach is a member (a `loading | empty | ready` union with no `error` is D wearing B's clothes).
- **A** — B, plus the error branch carries what it needs (`onRetry` lives *inside* `{status:"error"}`),
  so no caller can hand you a retry with nothing to retry.
- **A+** — The union is produced by the hook, not reconstructed by the page: `use*` returns the
  discriminated result and the screen has exactly one `switch`.

```
grep -rn "isLoading: boolean;\|isLoading?: boolean" webapp/src/components --include="*.tsx" | grep -v stories
grep -rnE "^\s+\w+\??: boolean;" webapp/src/components --include="*.tsx" | grep -v stories
```
Then ask of each hit: *how many of the 2^n combinations does the render actually distinguish?*

The tree carries dozens of `isLoading` props and **that is not dozens of defects** — a list shell whose
toolbar renders through every branch legitimately takes the triple. Exemplar:
`webapp/src/components/admin/practice-reviews/ReviewOutputSections.tsx`, whose `ReviewSectionState` is a
discriminated union over the states a section can be in rather than parallel flags.

## Dimension 2 — Composition: was the cheap rung tried before the expensive one?

- **D** — Context introduced for data that has an owner in the tree; or a compound API on a component
  whose structure is derived from one record, so a caller can compose a display that contradicts it.
- **C** — Props all the way down, including a prop drilled three levels that only the leaf reads.
- **B** — The ladder was walked: `children` before a prop, a prop before context, and the reason the next
  rung was not taken is legible from the code.
- **A** — B, and where composition was *rejected* the rejection is written down as the design: the file
  enumerates what the component must make unrepresentable, so the next reader does not re-open it.
- **A+** — A compound API exists and its Storybook cost is stated in the file: a `subcomponents` entry
  buys extra tabs in the ArgTypes doc block, while the Controls panel is driven by the story's `args`,
  which are the main component's — so a part needing its own controls stayed a prop deliberately.

Detection: `grep -rn "createContext" webapp/src/components` — for each, name the common ancestor. If one
exists and renders both consumers, it is D. The live example of the opposite failure is the review
route's `canAdminister`, drilled through `webapp/src/components/practice-trace/TracePage.tsx` →
`TraceRefusalAlert.tsx` / `TraceSignalTimeline.tsx` → `RefusalFixLink.tsx` for one leaf.

## Dimension 3 — Slot obligations, when `render=` is used

- **D** — A slotted element drops props, or is a fragment, or changes `button` → `div`.
- **C** — Props spread, but on an interior node with no comment saying why.
- **B** — `ref` forwarded, **every** received prop spread on the real DOM node, exactly one root element,
  same element type the primitive expected.
- **A** — B, and the file says which Base UI version's `render=` shape it targets. base-ui.com
  documents the latest release, not the pin, so a `render={(props, state) => …}` copied from the site
  may not exist at the installed version. Read the pin before copying.
- **A+** — A story proves the obligation: it queries the slotted element by **accessible name and role**
  after the slot, which fails if `aria-*` was dropped.

Anti-criterion: this kit is Base UI, not Radix, and `react/forbid-component-props` in
`webapp/.oxlintrc.json` already fails the build on `asChild`. A grep returning zero is a **pass
condition, not an achievement**.

## Dimension 4 — Do the Controls drive the real component?

- **D** — `meta` names a `component` but is typed bare `Meta`. (Already a build failure:
  `hephaestus/typed-story-meta`.)
- **C** — `render: () => <Thing fixed={…} />` — the args object is ignored, so the Controls panel edits
  nothing and `autodocs` publishes an API nobody can exercise.
- **B** — Every story is `args`-driven, or `render: (args) => <Harness {...args} />`; at least one story
  in the file is `export const Default: Story = {};`.
- **A** — B, and a controlled component closes its own loop through `Stateful` / `StatefulPatch`
  (`webapp/src/stories/stateful.tsx`) so the control moves the component **and** the `fn()` spy still fires.
- **A+** — `argTypes` narrow what inference got wrong (a union typed as `text`, an object arg set to
  `control: false`) rather than restating what `react-docgen` already produced.

```
grep -rn "render: () =>" webapp/src --include="*.stories.tsx"     # args-ignoring renders
grep -rn "render: (args" webapp/src --include="*.stories.tsx"     # the shape that keeps Controls
grep -rln "argTypes" webapp/src --include="*.stories.tsx"
```
Read the three counts against the story-file total (`find webapp/src -name '*.stories.tsx' | wc -l`).
Nearly every meta is `satisfies Meta<typeof>`; a minority of files use `argTypes`, and **that minority
is not a gap** — a component whose prop is one domain object has nothing explorable.

**Anti-criterion — the swallowed spy.** A stateful wrapper that *overrides* a callback from `args` makes
the `fn()` in `meta.args` unreachable: never assertable, Actions panel permanently empty, file looks well
instrumented. Detection: for each `fn()` in `meta.args`, grep for a JSX attribute of the same name that
is **not** `{...args}`. `webapp/src/components/admin/practice-editor/OccasionLifecycle.stories.tsx`
is the shape that survives: spread `{...args}`, patch only the props the wrapper holds state for.

## Dimension 5 — Which states does the file actually show?

- **D** — One story, no play, for a component with branches
  (`webapp/src/components/admin/teams/TeamTree.stories.tsx` and `.../mentor/Greeting.stories.tsx` are
  the shape).
- **C** — Default + each `variant` enum value.
- **B** — C plus every branch of the component's own state union, plus the two content edges the layout
  can lose to: longest realistic string and empty collection.
- **A** — B plus the 320px reflow viewport where the component has a horizontal axis.
  `webapp/.storybook/preview.tsx` defines `reflow` (320px) alongside `mobile`, `tablet`, `desktop` and
  `wide`.
- **A+** — B/A plus **dark**, for a component whose surface carries colour meaning. Dark is opt-in per
  story: `withThemeByClassName` defaults to `light` and Chromatic declares no `modes`, so the only
  thing that renders dark is a story that sets `globals: { theme: "dark" }`. Without one, contrast in
  dark is un-asserted for that component.

`grep -c "^export const .*: Story" <file>`, against
`grep -rc "^export const .*: Story" webapp/src --include="*.stories.tsx"` for the distribution it sits
in. A file far above the median is a maintenance question, not an achievement.

**Anti-criterion — coverage percentage.** A story count over `webapp/src/components/ui/**` is not a
gap however low it is. Those are a shadcn registry install that re-vendoring overwrites, so a story per
primitive documents upstream's API as if it were ours. Do not open that as work.

## Dimension 6 — Can the play function fail?

- **D** — `expect(canvas.getByRole(…)).toBeInTheDocument()` (`hephaestus/no-redundant-in-the-document`)
  or a bare `await expect(getBy(…))` (`vitest/valid-expect`). Both are already build failures.
- **C** — The play renders and asserts presence of text the story itself supplied — true whatever the
  component does with it.
- **B** — The assertion names a value the component **derived**: a label the registry produced, a count it
  computed, a disabled state, a URL. The expected value is written out as a literal.
- **A** — B, and an interaction that fires a callback asserts the spy:
  `expect(args.onX).toHaveBeenCalledWith(…)`.
- **A+** — The assertion targets the contract a look-alike would fail — `expectGenuinelyDisabled` /
  `expectUnavailable` (`webapp/src/test/controls.ts`) check focus behaviour, not the attribute.

```
grep -rln "userEvent\." webapp/src --include="*.stories.tsx"     # files driving an interaction
grep -rln "fn()" webapp/src --include="*.stories.tsx"             # files declaring a spy
grep -rln "expect(args\." webapp/src --include="*.stories.tsx"    # files asserting one
```

**Anti-criterion — spy count.** Far more files declare an `fn()` spy than assert one, and the
difference is **not** a defect list: `fn()` in `args` also drives the Actions panel. Only score D/C
when the play **does** trigger the spy and then ignores it.

**Anti-criterion — play-function count.** A large minority of files have no play at all, and Storybook
sanctions that shape: *"A render test is a simple version of an interaction test that only tests the
ability of a component to render successfully in a given state. That works fine for relatively simple,
static components like a Button."* (storybook.js.org/docs/writing-tests/interaction-testing). Adding a
play to a presentational badge is theatre. **Grade the component, not the file.**

## Dimension 7 — Accessibility beyond what axe sees

- **D** — A story suppresses the check: `parameters.a11y.test = "todo"` or `"off"`, or a rule disabled,
  without a comment naming the upstream issue.
- **C** — Green a11y panel.
- **B** — C, plus every control has an accessible name that disambiguates instances on one screen, and
  every state conveyed by colour has a second channel (icon required, not optional, in the registry).
- **A** — B, plus the story asserts the name/role it depends on, so a regression fails the suite rather
  than silently degrading.
- **A+** — The component's own invariant is asserted where axe is blind: that a disabled control is
  genuinely out of the tab order, or that an error field's `aria-describedby` points at the element
  carrying the message.

**Confirmation, not a finding: the baseline is at ceiling.** `webapp/.storybook/preview.tsx` sets
`a11y: { test: "error" }` project-wide, and `hephaestus/no-story-a11y-override` fails the build on any
per-story or per-meta override, so the count of them stays zero without anyone watching. The single
global exclusion (`[data-base-ui-focus-guard]`) cites the upstream bug beside it. Protect this; do not
propose work here.

**Anti-criterion — "axe is green".** The addon is axe-core, which
*"automatically catches up to 57% of WCAG issues"*
(storybook.js.org/docs/writing-tests/accessibility-testing). A green panel is C, never B. Requiring
`aria-label` in a props type is **house policy**; the normative obligation it serves is WCAG 2.2 SC
4.1.2, which says nothing about TypeScript.

## Dimension 8 — Is the published prose worth publishing?

Nearly every file carries `tags: ["autodocs"]`, so a JSDoc block above `meta` or above an exported story
**is** the component's documentation page.

- **D** — Prose restating the story's name (`/** Moving a group's worth in one action */` above `BulkSet`),
  or explaining how the assertion reaches the DOM — that belongs in a `//` inside the play function.
- **C** — Accurate description of what the story shows.
- **B** — Records something the reader cannot derive from the code below it: a rejected alternative, a trap,
  a why.
- **A** — B, and the block is addressed to somebody reading the *component*, not the test.
- **A+** — A file with no `autodocs` says in its meta why it opted out
  (`webapp/src/components/admin/practice-editor/SortableCatalogTree.stories.tsx` — the stories render
  a harness).

`node scripts/check-story-prose.ts` gates `<p>` only. For the D band there is no gate — it is a review
question: *delete this block; is anything lost?* Rather less than half the files carry a meta JSDoc and
rather less than half the stories carry one, so the absence of a block is not by itself a finding.

## Dimension 9 — Did the rule ship with its gate?

- **D** — A new rule added to prose that contradicts existing code, with no migration and no gate.
- **C** — Rule in prose, no gate.
- **B** — Rule in prose plus a mechanical check — an oxlint rule in `webapp/tools/oxlint/rules/`, or a
  repository gate in `scripts/` wired into `vp run check`.
- **A** — B, and the gate's own comment explains the neighbouring shapes it deliberately does *not*
  match, so nobody widens it into a nuisance — the house rules in `webapp/tools/oxlint/rules/` are the
  worked examples.
- **A+** — The gate is shrink-only: an allowlist entry that scans clean fails the build, so it cannot go
  stale (`scripts/check-presentational-components.ts`).

For each rule in a guidelines diff, `grep` for the thing it forbids across `webapp/src`. **A rule with a
0% adoption rate is not a rule** — it is a proposal, and it needs a migration before it is prose. A rule
with a meaningful violation rate, no gate and no behavioural consequence is pure style: delete it rather
than demote it.

---

## Anti-criteria — things that look like quality and are not

| Looks like quality | Why it is not |
|---|---|
| High story count per component | A variant story that reaches no new branch adds a Chromatic snapshot and a maintenance edge, nothing else. |
| Every `on*` prop wired to `fn()` | Feeds the Actions panel; proves nothing unless the play triggers it. |
| A play function on every story | Render tests are the sanctioned shape for static components. |
| Green a11y addon | axe-core catches up to 57% of WCAG issues. |
| `disableSnapshot: true` on interaction stories | Correct where it is paired with a `play`, which is nearly everywhere it appears. Not a finding either way. |
| Story coverage % including `components/ui/**` | A registry install; re-vendoring invalidates the story. |
| No `asChild` anywhere | This is Base UI. Absence is the baseline. |
| `argTypes` on every story file | Useless for a single domain-object prop. It is a correction layer, not coverage. |
| Long meta JSDoc | Published prose. Length is cost, not evidence. |
| A rule count in the guidelines | 14 rules of which 3 are wrong-as-scoped is worse than 10 that hold. |
| Prop count reduced by taking an object | Only a win if the object is one identity. `Pick<Wire, …>` yes; a bag named `options` no. |

## What A+ requires beyond A, in one line each

1. The discriminated union is produced by the hook, not rebuilt by the page.
2. A compound API states its Controls cost, or a prop stayed a prop because of it.
3. A story proves the slot kept its `aria-*`.
4. `argTypes` correct inference rather than restate it.
5. The story renders **dark**, which only a `globals: { theme: "dark" }` story does.
6. The assertion targets what a look-alike would fail, not what the attribute says.
7. The invariant asserted is one axe cannot see.
8. Opting out of `autodocs` says why, in the meta.
9. The gate is shrink-only.

## Scoring a PR

Score only the dimensions the diff touches. Report `dimension: grade — the one sentence that would move it
up a band`. A PR is **A overall** only if no touched dimension is below B and at least one is A. Any D is a
blocking finding. Do not average.
