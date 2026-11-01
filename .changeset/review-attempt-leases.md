---
"hephaestus": minor
---

A practice review attempt now holds a lease of its own, not a worker's. Model calls, results and status changes of an attempt that lost its lease are refused on every worker, so a requeued or recovered review never runs twice. Workers no longer need a stable worker id for reviews.

**Operators:** Stop all roles before the 1.0 upgrade. Apply the database migration before you start the new release. A review that was running when you stopped the previous release is recovered and runs again. Restore the verified backup to return to the previous release.
