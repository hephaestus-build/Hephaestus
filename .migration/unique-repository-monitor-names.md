#### 🔴 Stop every role before enforcing unique repository monitor names

The database now permits each repository name only once per workspace.
The upgrade stops if duplicate names exist in a workspace.
It does not delete or merge monitors, review settings, or sync progress.

Before you stop roles or start the upgrade, check for duplicates:

```sql
SELECT workspace_id, name_with_owner, count(*) AS monitors
FROM repository_to_monitor
GROUP BY workspace_id, name_with_owner
HAVING count(*) > 1;
```

If this query returns rows, reconcile the monitors against the provider’s repository IDs before you retry.
The database cannot choose which monitor to keep.
Preserve the review settings and sync progress that you need.

Stop the server, worker, and webhook roles before you upgrade.
Back up the database.
Start the roles only after the migration completes.
A failed migration can leave earlier changes of the release applied.
If the upgrade stops, restore the verified backup before you start the previous release.

Migration rollback removes the new constraint and preserves every monitor.
