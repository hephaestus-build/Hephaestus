---
"hephaestus": minor
---

Hephaestus no longer keeps five stores that nothing reads: the Outline document event log, the earlier consent notices and survey submissions, the unused idempotency key of review jobs, and the reasoning flag of language models. Before it drops the earlier survey submissions, the upgrade copies each answer that is not in the current survey records yet, so no answer is lost. **Operators:** 1.0 drops these; the upgrade stops the old version first. Back up the database before the upgrade. The upgrade deletes the rows of these stores, and no rollback restores them.
