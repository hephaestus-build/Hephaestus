#### 🔴 Stop every role before recording reviews whose evidence was too large

- **When:** in the window
- **Action:** Stop every runtime role before the migration. The previous server cannot read a review with the new stop reason.

A review whose captured evidence is larger than its sandbox holds now closes as Partial with its own stop reason, which says “The work’s evidence was larger than a review can hold.” Before, it said that something the review needs was not ready.

**Operators:** Use [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades). Drain work and stop every runtime role before the migration, then start only the upgraded roles. The previous server cannot read a review with the new stop reason, so do not run old and new servers together during this change. Alerts on `practice_review_ended_total` that leave out `readiness_refused` must also leave out `evidence_too_large`.

To roll back, stop every role and restore the verified backup from before the migration before starting the previous version.
