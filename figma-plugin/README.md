# Figma plugin

The Hephaestus host for FigJam and Figma Design.
It shows the team's authorized feedback, or links to it, through the host team grant (#5442).
It keeps no review, chat or state of its own.
It is not published.

## Build and import for development

1. Run `vp run build:figma-plugin`. It writes `figma-plugin/dist/code.js` and `figma-plugin/dist/ui.html`.
2. In the Figma desktop app, select **Plugins → Development → Import plugin from manifest…** and choose `figma-plugin/manifest.json`.
3. Open it from **Actions**: search for "Hephaestus".

The manifest allows only `https://hephaestus.build`, and `https://staging.hephaestus.build` for a development import.

## Rules the code keeps

- Opening the panel reads only counts of the selection. Nothing leaves the file before **Request review**.
- Every relaunch and plugin-data call checks that the node has the method. Some FigJam table nodes lack them, and an unguarded call throws.
- The only thing the plugin writes into a file is the **Review with Hephaestus** relaunch button, on an instructor's act.

Tests run with the tooling suite: `vp run test:tooling`.
