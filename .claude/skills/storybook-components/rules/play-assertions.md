# Play functions — can this one fail?

## A play function is optional, and adding one to a badge is theatre

A render test — a story that mounts and nothing else — is a sanctioned shape:

<!-- vale STE.SentenceLength = NO -->
*"A render test is a
simple version of an interaction test that only tests the ability of a component to render successfully
in a given state. That works fine for relatively simple, static components like a Button."*
<!-- vale STE.SentenceLength = YES -->

Source: storybook.js.org/docs/writing-tests/interaction-testing.

Grade the component, not the file.

Likewise an `fn()` spy that no play function triggers is **correct**: `fn()` in `args` also drives the
Actions panel. Only a spy the play *does* trigger and does not assert is a finding.

`hephaestus/play-must-assert` holds the floor a linter can see — a play that asserts *nothing*. It reads a `getBy*` used as a click target as an assertion, exactly as `vitest/expect-expect` does.
Whether the play checks the **outcome** remains the reviewer's call.

## An expectation must not be recomputed from what it is checking

`expect(rows).toHaveLength(FIXTURE.length)` makes a wrong component and a wrong test agree. So does
rebuilding the component's own branching to derive the URL it should have produced. Write the expected
values out as literals. If the literal table risks drifting, assert that its *keys* match the source's.

An assertion earns its place by naming a value the component **derived**, not text the story supplied.
Examples include a registry label, a computed count, a disabled state, or a URL.

## Assert the spy when the interaction fires one

`await expect(args.onSelect).toHaveBeenCalledWith("area-slug")`. An interaction whose only assertion is
that the DOM still contains the thing you clicked has not tested the interaction.

For "is this really disabled", use `expectGenuinelyDisabled` / `expectUnavailable`
(`webapp/src/test/controls.ts`) — they check focus behaviour, not just the attribute, so a look-alike fails.

## Portals: query with `screen`, not the canvas

Dialogs, popovers, selects and toasts render into a portal, so they are on `document` and not in the
story canvas.

## Overlays: never a bare `toBeVisible()` on something you just opened

Base UI mounts the panel with `data-starting-style`.
It clears that attribute one frame later.
During that frame, the panel computes to `opacity: 0`, and a mounted element reads as invisible.
**This is not an animation *duration* problem.**
The Playwright context already requests `reducedMotion: "reduce"`, and the media query matches.
Forcing every duration to 1ms does not fix it.

Use `expectSettledVisible` from `@/stories/overlay`.
It waits for the starting-style frame to pass and the enter transition to finish before asserting.
It takes the element you care about, not the panel.
The assertion target is usually a `<dt>` or `<p>` well inside the popup.
The *ancestor* is transparent, so the helper looks upward for both signals. Reach for `settledPopup()`
from the same module when you need the panel itself — the measuring assertions do, because a mid-flight
`scale(.95)` would let a too-wide popup pass.

## Published prose above a story is documentation, not a comment

Nearly every story file carries `tags: ["autodocs"]`, so a JSDoc block above `meta` or above an exported
story **is** the component's Docs page. It earns its place only if it records something the reader cannot derive from the code below.
This can be a rejected alternative, a real trap, or a reason.
Address it to somebody reading the *component*, not the test. Restating the story's name is the common failure. Notes about
how the assertion reaches the DOM are the other, and those belong in a `//` inside the play function.

Storybook renders these blocks with `markdown-to-jsx`. Separate paragraphs with a blank comment line. A
Java-style `<p>` emits a stray empty paragraph, and `scripts/check-story-prose.ts` fails the build on
one.
