#### 🔴 Stop every role before product feedback renames its stored states

Stop every role before this upgrade.
Create and verify a database backup.
The migration renames the state `EMAILED` to `SAVED` and the route `EMAIL` to `ADMINS`, so the previous application cannot read those rows.
The rows and their reports stay as they are.
To return to the previous release, follow [Rollback](https://docs.hephaestus.build/admin/upgrade#rollback) and restore that backup first.
