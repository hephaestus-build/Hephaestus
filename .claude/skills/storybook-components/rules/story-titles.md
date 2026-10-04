# Story titles and the sidebar

A story has no `title`.
Storybook files it by its path below `src/components/`.
`.storybook/main.ts` strips that prefix.
Thus, `webapp/src/components/admin/workspace-llm/ModelPicker.stories.tsx` becomes `admin/workspace-llm/ModelPicker`.
The sidebar's top level is `admin/…`, `common/…`, `ui/…`.

The few stories under `src/runtime/` keep that segment.
Thus, `FeatureFlagDevTools.stories.tsx` is `runtime/feature-flags/FeatureFlagDevTools`. A story id is the kebab-cased title —
`admin-workspace-llm-modelpicker--default` — which is what a `?path=/story/…` link and
`webapp/scripts/export-assets.ts` carry. `hephaestus/prefer-auto-story-title` rejects any explicit
`title`.

`.storybook/manager.ts` renders each segment in start case: *Admin › AI › Model Picker*.
A map in that file spells the acronyms and brands that the filename rule writes as words.
Examples include `LLM`, `SCM`, `UI`, and `GitLab`. A new acronym in a directory or component name goes into the map, or it
reads as *Sso*.

- **The file tree is the grouping.** To move a story in the sidebar, move the component. A directory
  that reads badly in the sidebar reads badly in an import path too, so rename it there.
- **Two story files never share a directory and a stem**.
  A story file never shares its stem with a sibling directory.
  Otherwise, Storybook files a leaf and a folder under one name. `gate:stories` fails on
  either.
- **A story never repeats its directory's name** — `detail-drawer/DetailDrawer.stories.tsx`,
  `index.stories.tsx` — while the directory holds any other story. Storybook folds that file into the
  parent node (`…/detail-drawer`, case-insensitively), where it shadows every sibling. `gate:stories`
  fails on it too.
- **A cross-cutting regression suite is not a component.** A file with no `component`, covering several
  primitives at once, lives beside them in `webapp/src/components/ui/` with a kebab-case stem — see
  `overlay-reflow.stories.tsx`.
