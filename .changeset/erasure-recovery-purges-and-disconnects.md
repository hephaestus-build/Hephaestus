---
"hephaestus": minor
---

Erasure recovery after a database restore now reapplies workspace purges and disconnects, not only person erasures. The `erasure-recovery` profile turns background work off, and startup stops if anything turns it on again.

**Operators:** A recovery manifest now uses version 3 and lists `disconnects`, `workspacePurges`, `collectionErasures` and `messageDeletions` beside `people`; a manifest of version 1 or 2 stops startup. After each of these erasures, save its IDs in the restricted incident record. The server logs `workspace.purged`, `connection.erased`, `outline.collection_erased` and `conversation.message_deleted` events as a cross-check. See Reapply erasure decisions before processing resumes in Backup and restore.
