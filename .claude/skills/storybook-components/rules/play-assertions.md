# Play functions — can this one fail?

## A play function is optional, and adding one to a badge is theatre

A render test — a story that mounts and nothing else — is a sanctioned shape: *"A render test is a
simple version of an interaction test that only tests the ability of a component to render successfully
in a given state. That works fine for relatively simple, static components like a Button."*
(storybook.js.org/docs/writing-tests/interaction-testing). Grade the component, not the file.

Likewise an `fn()` spy that no play function triggers is **correct**: `fn()` in `args` also drives the
Actions panel. Only a spy the play *does* trigger and does not assert is a finding.

`hephaestus/play-must-assert` holds the floor a linter can see — a play that asserts *nothing*. It reads
a `getBy*` used as a click target as an assertion, exactly as `vitest/expect-expect` does, so whether the
play checks the **outcome** is still the reviewer's call.

## An expectation must not be recomputed from what it is checking

`expect(rows).toHaveLength(FIXTURE.length)` makes a wrong component and a wrong test agree. So does
rebuilding the component's own branching to derive the URL it should have produced. Write the expected
values out as literals; if the literal table risks drifting, assert that its *keys* match the source's.

The assertion that earns its place names a value the component **derived** — a label the registry
produced, a count it computed, a disabled state, a URL — not text the story itself supplied.

## Assert the spy when the interaction fires one

`await expect(args.onSelect).toHaveBeenCalledWith("area-slug")`. An interaction whose only assertion is
that the DOM still contains the thing you clicked has not tested the interaction.

For "is this really disabled", use `expectGenuinelyDisabled` / `expectUnavailable`
(`webapp/src/test/controls.ts`) — they check focus behaviour, not just the attribute, so a look-alike fails.

## Portals: query with `screen`, not the canvas

Dialogs, popovers, selects and toasts render into a portal, so they are on `document` and not in the
story canvas.

## Overlays: never a bare `toBeVisible()` on something you just opened

Base UI mounts the panel with `data-starting-style` and clears it a frame later, so for that one frame
the panel computes to `opacity: 0` and a mounted element reads as invisible. **This is not an animation
*duration* problem** — the Playwright context already requests `reducedMotion: "reduce"`, the media
query matches, and forcing every duration to 1ms does not fix it.

Use `expectSettledVisible` from `@/stories/overlay`, which waits for the starting-style frame to pass and
for the enter transition to finish before asserting. It takes the element you actually care about, not
the panel: the assertion target is usually a `<dt>` or a `<p>` well inside the popup, and it is the
*ancestor* that is transparent, so the helper looks upward for both signals. Reach for `settledPopup()`
from the same module when you need the panel itself — the measuring assertions do, because a mid-flight
`scale(.95)` would let a too-wide popup pass.

## Published prose above a story is documentation, not a comment

Nearly every story file carries `tags: ["autodocs"]`, so a JSDoc block above `meta` or above an exported
story **is** the component's Docs page. It earns its place only if it records a rejected alternative, a
real trap, or a why the reader cannot derive from the code below it — and it is addressed to somebody
reading the *component*, not the test. Restating the story's name is the common failure; notes about
how the assertion reaches the DOM are the other, and those belong in a `//` inside the play function.

Storybook renders these blocks with `markdown-to-jsx`. Separate paragraphs with a blank comment line; a
Java-style `<p>` emits a stray empty paragraph, and `scripts/check-story-prose.ts` fails the build on
one.
