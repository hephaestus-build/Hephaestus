#### 🔴 Stop every role before practices stay current on their own sources

- **When:** in the window
- **Action:** Do a [stopped schema upgrade](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades): scale the server to zero, run the migration, then start the upgraded server.

The migration rewrites the stored tree fingerprint of each recorded pull request review to the new format, which is its commit pair.
A server of the old release and one of the new release judge these fingerprints in different formats.
If both run at the same time, either can read a result as changed work and end it, although the work did not change.

**Operators:** Stop every runtime role before the migration, and start only the upgraded server afterward.
To roll back, stop every role and restore the verified backup from before the migration before you start the previous version.
