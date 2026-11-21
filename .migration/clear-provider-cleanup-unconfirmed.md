#### 🔴 List the disconnects whose provider cleanup was never confirmed

- **When:** before the window
- **Action:** Only if this instance ever ran 1.0.0-rc.3, rc.4 or rc.5: list the connections that still show "Check … for what Hephaestus left", and have their workspace admins remove what they left at the provider.

Releases 1.0.0-rc.3 to rc.5 showed this warning on a disconnected integration when they could not confirm that the provider removed the app, token or webhook. Since rc.8, **Instance administration → Provider removals** records such a removal instead. The old warning stayed on the connection until it was reconnected. This upgrade removes the warning and clears it from the database.

Before the upgrade, list the connections that still carry it, with read-only `psql` access to the application database:

```sql
SELECT w.slug, c.kind, c.display_name, c.instance_key, c.updated_at
FROM connection c JOIN workspace w ON w.id = c.workspace_id
WHERE c.attention_problem = 'PROVIDER_CLEANUP_UNCONFIRMED';
```

Send each row to the admins of that workspace, who have access to the provider. They remove what Hephaestus set up there: the Hephaestus app installation in GitHub, the webhook in the GitLab group or its projects, the Hephaestus app in Slack, or the webhook in Outline. Then they revoke the token or key that the workspace gave Hephaestus.

**Operators:** An instance that never ran rc.3, rc.4 or rc.5 has no such rows, and needs no action. From the release before this one, old and new servers can run together during this upgrade. An upgrade from rc.3, rc.4 or rc.5 skips releases, so it stops every role first. To bring the cleared warnings back, restore the backup from before the migration.
