---
"hephaestus": patch
---

Two bundled practices now judge what a change actually adds. "Ship a preview with each new view" accepts a preview that renders a new view through the screen hosting it, and no longer asks for an extra preview of a view that only starts and stops work for content that is already previewed. Navigation, toolbars and other parts such a view shows itself still need a preview. "Change dependencies deliberately" no longer reviews a change that only edits a project file's internal targets, test targets or schemes; it applies when an external dependency, its version, its source or its resolution changes. Workspaces adopt the new definitions through the usual practice update.
