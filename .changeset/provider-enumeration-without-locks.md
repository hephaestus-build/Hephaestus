---
"hephaestus": minor
---

GitHub repository discovery no longer holds workspace or connection locks while GitHub answers. You can pause or reconnect during discovery. An answer from the earlier connection is discarded, so it cannot change the current repository list.

**Operators:** Stop every server before this upgrade, then start the new release. Repository access events must advance the same revision on every replica. See *Stop every role before the repository enumeration upgrade* in the migration guide.
