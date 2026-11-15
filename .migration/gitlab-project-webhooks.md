#### 🔴 Stop every role before GitLab project webhooks

- **When:** in the window
- **Action:** Stop every server before the upgrade. The previous server cannot read a GitLab connection that holds project webhooks or a refused-webhook notice.

On a GitLab without group webhooks, Hephaestus now registers a webhook on each monitored project, and it records on the connection when GitLab refuses every webhook. A previous server cannot read these records.

**Operators:** Apply this change in the stopped window before the 1.0.0 cut. Use [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades). Stop every runtime role and start only the upgraded roles afterward. Do not run old and new servers together during this change.

To roll back, stop every role and restore the verified backup from before the upgrade before you start the previous version. A previous version cannot load a GitLab connection that holds project webhooks or a refused-webhook notice.
