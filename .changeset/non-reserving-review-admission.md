---
"hephaestus": minor
---

Practice reviews wait in the queue when their review quota has no room or their model endpoint has background calls waiting. Queued reviews no longer hold an unused provider grant. The parent review starts in the transaction that claims and leases its job. Claims prefer workspaces with fewer running reviews and requested reviews, while aging keeps old backfill eligible.

**Operators:** Use the stopped rc.1 cutover: stop every runtime role before database migration, then start only the new release. The migration retires unused claim reservations. Do not restart older workers against the migrated database.
