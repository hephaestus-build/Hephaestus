#### 🔴 Stop every role before conversation runs record their owner

- **When:** in the window
- **Action:** Use the [stopped upgrade](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades). The migration records each conversation run's owner. A server of the previous release cannot queue a conversation run on the new schema.

An erasure that stopped before the upgrade resumes on the new release.
The migration deletes the turns that such an erasure left without their question, with their runs.

If the migration stops with "conversation runs have no turn with its question or digest entry", the database holds a run whose turn has no question outside an erasure. Restore the verified pre-upgrade backup, and open an issue in the Hephaestus repository with the count.
