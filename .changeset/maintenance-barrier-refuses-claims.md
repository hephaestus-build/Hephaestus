---
"hephaestus": patch
---

While a database migration runs, servers claim no queued practice review, conversation turn, outbox action or provider event, and start no sandbox. Work that started before the migration finishes. A test email or another action that must run at once answers that Hephaestus is updating. When the migration ends, also when it fails, the servers take the waiting work again ([Work during a migration](https://docs.hephaestus.build/admin/runtime-roles#work-during-a-migration)).
