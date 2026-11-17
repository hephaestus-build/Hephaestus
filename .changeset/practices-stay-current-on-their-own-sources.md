---
"hephaestus": patch
---

Automatic pull request reviews no longer withhold their practices when someone pushes to another branch, adds a tag, or when a bot comments on the pull request. A practice now counts as outdated only when a source it reads changes: the pull request's commits, its record, or a source the practice declares, such as the discussion. Results recorded before this release keep their fingerprints: a push to another branch or a new tag no longer ends them, and they still count as outdated when any source they recorded changes, as before.

**Operators:** This upgrade must be stopped: scale the server to zero, migrate, then start it. See [Stop every role before practices stay current on their own sources](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades).
