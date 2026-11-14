---
"hephaestus": patch
---

The upgrade now checks that the earlier closing of queued role notices still holds. If a server of the previous release queued the old general notice again because it ran beside the upgrade, the migration stops with a message instead of continuing. Then stop every role, restore the backup taken before the upgrade, and upgrade again in the stopped window. A database rollback past this point also stops and names that backup.
