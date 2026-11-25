#### 🔴 Stop every role before you enable the journal bucket

- **When:** before the window
- **Action:** If you use the journal bucket, create its credential Secret. Use the stopped upgrade procedure so that all server replicas begin using the same bucket and prefix.

The chart uses the backup bucket for the privacy journal by default. Use the separate credential and prefix permissions in the [backup and restore guide](https://docs.hephaestus.build/admin/backup-restore#keep-the-privacy-journal-outside-the-database).
