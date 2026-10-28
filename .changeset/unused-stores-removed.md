---
"hephaestus": minor
---

Hephaestus no longer keeps five unused stores: the Outline event log, earlier consent notices, earlier survey submissions, review-job idempotency keys and model reasoning flags.
Before it removes earlier survey submissions, the 0.95.0 upgrade copies each submission that has no current participation record.
Existing participation records keep their current answers.

**Operators:** stop every runtime role before the 0.95.0 upgrade.
Back up the database and verify that it restores first.
The upgrade deletes the rows in these unused stores.
An image rollback cannot restore those rows. Recovery needs the verified backup.
