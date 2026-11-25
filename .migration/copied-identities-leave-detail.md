#### 🔴 Stop every role before copied identities leave the review item detail

- **When:** before the window, in the window
- **Action:** Before the window, complete every admitted person data erasure on the current release. In the window, use the [stopped upgrade](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades): stop every role, run the migration, then start the new release. Keep the final restore point that you take while the server is stopped, because a rollback needs it.

A review item now records each person whose words its capture copied as a row of its own. The migration moves every earlier list into these rows and removes it from the review item's detail. A server of the previous release finds a person's copies only in that detail. If both releases run together, that server cannot find any copy. An erasure that it runs then leaves those copied words in place. A rollback to the previous release has the same gap, so roll back only by restoring that final restore point.

The new release does not resume an erasure that the previous release admitted, because the previous release selected it from the old detail. The migration stops if a person data erasure is still erasing or failed, and its error names each request. Before the window, list them:

```sql
SELECT id, state, created_at FROM person_data_request WHERE state IN ('ERASING', 'FAILED');
```

Let each erasing request complete. Resume each failed request after you correct the cause that it reports. When the query returns no rows, start the window.

**Recover when the migration stops on an erasure.** An erasure can still start after this query and before the last server of the previous release stops. The migration then stops with every server offline, after earlier change sets of the release have committed. Running the migration again stops again, and the previous release must not run on that database. Recover as follows:

1. Keep every role of the new release stopped.
2. Follow [If migration fails](https://docs.hephaestus.build/admin/upgrade#if-migration-fails). Restore the final restore point that you took after the previous release stopped, and start the previous release. Never restore a backup from before the window: it predates the erasures that the error names, so it brings erased data back.
3. Let the previous release complete each erasure that the migration error names.
4. Stop the previous release, and take and verify a new final restore point. It is now the only restore point for this upgrade: never restore an earlier one.
5. Start the stopped upgrade again.

A preview that the previous release made is refused after the upgrade. Create a new preview for that person.
