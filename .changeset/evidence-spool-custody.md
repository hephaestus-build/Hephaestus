---
"hephaestus": patch
---

Temporary Git snapshots now keep their workspace and cleanup owner in the evidence home. Failed removal stays visible to reconciliation and keeps capture admission until the files are gone. Review gateway archives use the review attempt's existing receipt and folder lease.

Server startup removes abandoned spool copies before accepting new work. If cleanup fails, startup stops; restore the evidence volume's access and free space, then restart. A restart no longer leaves these copies for the attempt-retention grace period. Existing evidence cleanup alerts also report failed spool removal. No new storage service or database table is required.
