# Story titles and the sidebar

A story has no `title`. Storybook files it by its path under `src`, so
`webapp/src/components/admin/ai/ModelPicker.stories.tsx` is `components/admin/ai/ModelPicker`, and
`.storybook/manager.ts` renders each segment in start case — *Components › Admin › Ai › Model Picker*.
`hephaestus/prefer-auto-story-title` rejects any explicit `title`.

- **The file tree is the grouping.** To move a story in the sidebar, move the component. A directory
  that reads badly in the sidebar reads badly in an import path too, so rename it there.
- **Two story files never share a directory and a stem**, and a story file never shares its stem with a
  sibling directory: Storybook would file a leaf and a folder under one name. `gate:stories` fails on
  either.
- **A cross-cutting regression suite is not a component.** A file with no `component`, covering several
  primitives at once, lives beside them in `webapp/src/components/ui/` with a kebab-case stem — see
  `overlay-reflow.stories.tsx`.
