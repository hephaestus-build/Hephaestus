# Traps — things that pass while being wrong

Everything here fails **silently**. A green run is not evidence against any of them.

## 1. A just-opened Base UI overlay reads as invisible for one frame

Base UI mounts the panel with `data-starting-style` and clears it a frame later, so the panel computes
to `opacity: 0` and a bare `toBeVisible()` fails on a perfectly mounted element. It is **not** a
duration problem: the Playwright context already requests `reducedMotion: "reduce"`, the media query
matches, and forcing every duration to 1ms does not fix it. Use `expectSettledVisible` /
`settledPopup()` from `webapp/src/test/overlay.ts`.

## 2. `animation.finished` **rejects** when the animation is cancelled

`AbortError`, not a resolution — and a popup that re-positions while opening replaces its own enter
animation routinely. Any settle helper must `.catch()` the rejection and treat it as an outcome
(`webapp/src/test/overlay.ts`). Without the catch the helper throws on the ordinary path.

## 3. A hook suppression aimed at the `useEffect` line suppresses nothing

`webapp/AGENTS.md` § Linting owns this: the two effect rules report on the `setState` line inside the
effect, one `setState` usually trips both, and a directive naming `react-hooks/rules-of-hooks` silences
every hook diagnostic in the component while the build fails about the directive instead.

## 4. One story's MSW handlers answer for the whole Docs page

`webapp/AGENTS.md` § Container/presentation split owns this: a story file installs no handlers at
all, and `scripts/check-presentational-components.ts` enforces it. The symptom is a Docs page that
renders every sibling as the error state while every isolated story stays green.

## 5. `test:storybook` does not run the brand-asset check — CI does, right after

The `Webapp: Stories` job runs the webapp package's `export:assets` immediately after
`test:storybook` and fails if any exported asset is dirty afterwards, so the job can go red having
printed a clean pass line. If a change moves or renames a story that exports an asset, run
`vp run --filter webapp export:assets` and commit the result; `/fix-ci` lists the paths.

## 6. A hand-rolled stateful wrapper swallows the spy in `meta.args`

If the wrapper passes its own `onChange` instead of `{...args}`, the `fn()` declared in `meta.args` can
never be called, never be asserted, and never appears in the Actions panel — while the file looks fully
instrumented. Use `Stateful` / `StatefulPatch` from `webapp/src/stories/stateful.tsx`.
