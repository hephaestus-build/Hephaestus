#### 🔴 Create the backup reporter credential

- **When:** before the window
- **Action:** For a chart-managed PostgreSQL backup, rerun the release key setup with backups enabled. See [privacy journal retention](https://docs.hephaestus.build/admin/upgrade-notes/privacy-journal).

This adds the `DATABASE_BACKUP_REPORTER_PASSWORD` key to the release Secret.
The role setup uses it to give the backup Jobs a login that writes only the oldest recovery point.
