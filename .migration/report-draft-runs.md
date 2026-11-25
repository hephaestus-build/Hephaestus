#### 🔴 Stop every role before migrating report drafts

- **When:** in the window
- **Action:** Stop every server replica and take a verified database backup before you run the migration.

Follow [Database maintenance](https://docs.hephaestus.build/admin/maintenance#database-maintenance).
The migration moves pending report drafts into the run queue and settles any open calls from the old workers.
Start only the new server version after migration.
To return to the previous version, restore the verified backup before you start it.
