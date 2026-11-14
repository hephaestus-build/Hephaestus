#### 🔴 Stop every role before changing role-notice delivery

Role-change emails now tell the affected person and the other administrators who made the change and where to review it. The migration closes queued copies of the previous general notice. It keeps completed receipts and does not repeat a send whose outcome is unknown.

**Operators:** Apply this change in the stopped window before the 1.0.0 cut. Use [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades). Stop every runtime role before the migration and start only the upgraded roles afterward. An old server can queue a previous notice after the migration has closed it, so do not run old and new servers together during this change.

To roll back, stop every role and restore the verified backup from before the migration before starting the previous version.
