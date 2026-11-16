#### 🔴 Write erasure recovery manifests as version 3

- **When:** before the window
- **Action:** Add the disconnects, the workspace purges, the erased Outline collections and the deleted conversation messages to each erasure recovery manifest, as [Erasure recovery](https://docs.hephaestus.build/admin/erasure-recovery) describes.

Erasure recovery now reapplies workspace purges, disconnects, erased Outline collections and deleted conversation messages, not only person erasures.
A recovery manifest uses version 3 and lists `disconnects`, `workspacePurges`, `collectionErasures` and `messageDeletions` beside `people`. A manifest of version 1 or 2 stops startup.

**Operators:** After each of these erasures, save its IDs in the restricted incident record, as you do for a person erasure. The log events of [Erasure events](https://docs.hephaestus.build/admin/log-records#erasure-events) carry them.
Convert any prepared manifest of version 1 or 2 before a recovery.
[Reapply erasures after a restore](https://docs.hephaestus.build/admin/erasure-recovery#write-the-manifest) gives the format.
