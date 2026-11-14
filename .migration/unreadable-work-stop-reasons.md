#### 🔴 Stop every role before changing the feedback stop reasons

Feedback that cannot reach the work now records whether the work was removed, is no longer visible, or lost consent. The migration replaces the stop reason `APPROVAL_NO_LONGER_ELIGIBLE` with `ITEM_NOT_VISIBLE` on stored feedback. Old rows do not record whether access or consent was lost, so they all read as lost access.

**Operators:** Apply this change in the stopped window before the 1.0.0 cut. Use [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades). Stop every runtime role before the migration and start only the upgraded roles afterward. An old server cannot read the new stop reasons, so do not run old and new servers together during this change.

To roll back, stop every role and restore the verified backup from before the migration before starting the previous version.
