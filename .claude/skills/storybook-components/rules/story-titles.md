# Story titles and the sidebar

A story has no `title`. Storybook files it by its path below one of the two roots in
`.storybook/main.ts`: `src/components/` is stripped, so
`webapp/src/components/admin/ai/ModelPicker.stories.tsx` is `admin/ai/ModelPicker` and the sidebar's
top level is `admin/…`, `common/…`, `ui/…`; `src/integrations/` is titled `App runtime`, so
`FeatureGate.stories.tsx` is `App runtime/feature-flags/FeatureGate`. A story id is the kebab-cased
title — `admin-ai-modelpicker--default` — which is what a `?path=/story/…` link and
`webapp/scripts/export-assets.ts` carry. `hephaestus/prefer-auto-story-title` rejects any explicit
`title`.

`.storybook/manager.ts` renders each segment in start case — *Admin › AI › Model Picker* — and
upper-cases the acronyms the filename rule writes as words (`ai`, `cta`, `faq`, `llm`, `scm`, `ui`,
`xp`). A new acronym in a directory or component name goes into that set, or it reads as *Sso*.

- **The file tree is the grouping.** To move a story in the sidebar, move the component. A directory
  that reads badly in the sidebar reads badly in an import path too, so rename it there.
- **Two story files never share a directory and a stem**, and a story file never shares its stem with a
  sibling directory: Storybook would file a leaf and a folder under one name. `gate:stories` fails on
  either.
- **A story never repeats its directory's name** — `detail-drawer/DetailDrawer.stories.tsx`,
  `index.stories.tsx` — while the directory holds any other story. Storybook folds that file into the
  parent node (`…/detail-drawer`, case-insensitively), where it shadows every sibling. `gate:stories`
  fails on it too.
- **A cross-cutting regression suite is not a component.** A file with no `component`, covering several
  primitives at once, lives beside them in `webapp/src/components/ui/` with a kebab-case stem — see
  `overlay-reflow.stories.tsx`.
