#### 🔴 Stop every role before the provider-ref columns are dropped

- **When:** in the window
- **Action:** Do a [stopped schema upgrade](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades): scale the server to zero, run the migration, then start the upgraded server.

The migration drops the columns `git_refs_fingerprint` and `git_refs_read_id` of `repository_to_monitor`.
A server of the previous release still writes these columns, so it fails while it runs against the migrated schema.

**Operators:** Stop every runtime role before the migration, and start only the upgraded server afterward.
To roll back, stop every role and restore the verified backup from before the migration before you start the previous version.
