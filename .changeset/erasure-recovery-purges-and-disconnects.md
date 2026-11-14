---
"hephaestus": minor
---

Erasure recovery after a database restore now reapplies workspace purges and disconnects, not only person erasures. The `erasure-recovery` profile turns background work off, and startup stops if anything turns it on again.

**Operators:** A recovery manifest now uses version 2 and lists `disconnects` and `workspacePurges` beside `people`; a version 1 manifest stops startup. After a workspace purge or a disconnect, save the workspace ID and the connection ID in the restricted incident record. The server also logs `workspace.purged` and `connection.erased` events as a cross-check. See Reapply erasure decisions before processing resumes in Backup and restore.
