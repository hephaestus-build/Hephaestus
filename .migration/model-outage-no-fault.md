#### 🔴 Stop every role before recording provider-unavailable reviews

A review that the model provider leaves unanswered now waits for the provider without spending a retry. If the provider stays unavailable past the review's bound, the review closes as Partial with the new stop reason *Provider unavailable*.

**Operators:** Use [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades). Drain work and stop every runtime role before the migration, then start only the upgraded roles. The previous server cannot read a review with the new stop reason, so do not run old and new servers together during this change.

To roll back, stop every role and restore the verified backup from before the migration before starting the previous version.
