#### 🔴 Write erasure recovery manifests as version 2

Erasure recovery now reapplies workspace purges and disconnects, not only person erasures.
A recovery manifest uses version 2 and lists `disconnects` and `workspacePurges` beside `people`. A version 1 manifest stops startup.

**Operators:** After a workspace purge or a disconnect, save the workspace ID and the connection ID in the restricted incident record, as you do for a person erasure.
Convert any prepared version 1 manifest before a recovery.
[Reapply erasures after a restore](https://docs.hephaestus.build/admin/erasure-recovery#write-the-manifest) gives the format.
