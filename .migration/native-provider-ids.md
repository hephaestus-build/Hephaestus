#### 🔴 Stop every role before requiring native provider IDs

Stop every role before this upgrade.
Create and verify a database backup.
Keep the previous application stopped until the migration completes.
Its bootstrap can still write monitors without provider IDs.
To return to the previous release after any refusal, restore that backup first.
Earlier migrations in the release can already have committed.

The migration preserves monitor IDs, sync checkpoints, retained work, and stop receipts.
It fills missing IDs only from provider identities on the connection's origin that the workspace owns.
A repository needs that workspace's repository item.
A GitLab group needs that workspace's organization connection.
The migration converts path-only GitLab stops to native IDs.
It creates no receipts for old stops.

If a selection is unresolved, invalid, or would duplicate a monitor, the migration refuses before this change writes data.
The refusal names every affected row and links this entry.
The migration never deletes a selection.

Run this read-only query before the upgrade.
It reports only the selections that require operator action.
`row_id` names the monitor for `monitor` and the connection for every other kind.
`workspace_retained_items` counts the whole workspace, including removed items.
It is context, not the selection's retained-work count.
The removal commands check the verified project ID and path instead.

`unresolved_repository_items` counts repository items whose native source no longer exists.
These items prevent removal of a real project because the query cannot prove their project IDs.

```sql
WITH monitor_ids AS (
    SELECT m.id, m.workspace_id, m.name_with_owner AS selection,
        COALESCE(m.native_id, (
            SELECT min(r.native_id) FROM repository r
            JOIN identity_provider p ON p.id = r.provider_id
            JOIN connection c ON c.workspace_id = m.workspace_id AND c.kind = p.type
                AND c.config->>'serverUrl' = p.server_url AND c.state IN ('ACTIVE','SUSPENDED')
            WHERE lower(r.name_with_owner) = lower(m.name_with_owner) AND r.native_id > 0
                AND EXISTS (SELECT 1 FROM item i WHERE i.workspace_id = m.workspace_id
                    AND i.kind = 'scm.repository' AND i.parent_id IS NULL AND i.source_id = r.id)
            HAVING count(DISTINCT r.native_id) = 1
        )) AS native_id
    FROM repository_to_monitor m
), group_ids AS (
    SELECT c.id, c.workspace_id, w.account_login AS selection,
        c.config->'gitlabGroupId' AS stored_id,
        (SELECT o.native_id FROM organization o
         JOIN identity_provider p ON p.id = o.provider_id
         WHERE o.id = w.organization_id AND o.native_id > 0
             AND p.type = c.kind AND p.server_url = c.config->>'serverUrl') AS resolved_id
    FROM connection c JOIN workspace w ON w.id = c.workspace_id WHERE c.kind = 'GITLAB'
), problems AS (
    SELECT 'monitor' AS kind, m.id AS row_id, m.workspace_id, m.selection,
        'No positive provider ID with workspace-owned evidence' AS reason
    FROM monitor_ids m WHERE m.native_id IS NULL OR m.native_id <= 0
    UNION ALL
    SELECT 'monitor', m.id, m.workspace_id, m.selection, 'Duplicate provider ID in this workspace'
    FROM monitor_ids m WHERE m.native_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM monitor_ids other WHERE other.workspace_id = m.workspace_id
            AND other.native_id = m.native_id AND other.id <> m.id)
    UNION ALL
    SELECT 'group', g.id, g.workspace_id, g.selection, 'No positive provider group ID with workspace-owned evidence'
    FROM group_ids g WHERE CASE
        WHEN g.stored_id IS NULL OR g.stored_id = 'null'::jsonb THEN g.resolved_id IS NULL
        WHEN jsonb_typeof(g.stored_id) = 'number' AND g.stored_id::text ~ '^[1-9][0-9]*$'
            THEN g.stored_id::text::numeric > 9223372036854775807
        ELSE true END
    UNION ALL
    SELECT 'stop', c.id, c.workspace_id, path.value, 'No positive provider ID with workspace-owned evidence'
    FROM connection c
    CROSS JOIN LATERAL jsonb_array_elements_text(
        COALESCE(c.config#>'{projectExclusions,unresolvedPaths}', '[]')) path(value)
    WHERE c.kind = 'GITLAB' AND (
        SELECT count(DISTINCT r.native_id) FROM repository r
        JOIN identity_provider p ON p.id = r.provider_id
        WHERE p.type = c.kind AND p.server_url = c.config->>'serverUrl'
            AND lower(r.name_with_owner) = lower(path.value) AND r.native_id > 0
            AND EXISTS (SELECT 1 FROM item i WHERE i.workspace_id = c.workspace_id
                AND i.kind = 'scm.repository' AND i.parent_id IS NULL AND i.source_id = r.id)
    ) <> 1
    UNION ALL
    SELECT 'stop-id', c.id, c.workspace_id, value::text, 'Project stop ID must be a positive JSON integer'
    FROM connection c
    CROSS JOIN LATERAL jsonb_array_elements(
        COALESCE(c.config#>'{projectExclusions,nativeIds}', '[]')) ids(value)
    WHERE c.kind = 'GITLAB' AND CASE
        WHEN jsonb_typeof(value) = 'number' AND value::text ~ '^[1-9][0-9]*$'
            THEN value::text::numeric > 9223372036854775807
        ELSE true END
)
SELECT COALESCE(jsonb_agg(jsonb_build_object(
    'kind', kind, 'row_id', row_id, 'workspace_id', workspace_id,
    'selection', selection, 'reason', reason,
    'workspace_retained_items', (SELECT count(*) FROM item i WHERE i.workspace_id = problems.workspace_id),
    'unresolved_repository_items', (SELECT count(*) FROM item i
        WHERE i.workspace_id = problems.workspace_id AND i.kind = 'scm.repository' AND i.parent_id IS NULL
            AND NOT EXISTS (SELECT 1 FROM repository r WHERE r.id = i.source_id)))
    ORDER BY kind, row_id, selection), '[]') AS issues
FROM problems;
```

For a selection you can repair, use a credential with access to its configured origin.
For GitHub, read `GET /repos/{owner}/{repo}` and record `id`.
For GitLab projects, read `GET /api/v4/projects/{encoded_path}` and record `id`.
For GitLab groups, read `GET /api/v4/groups/{encoded_path}` and record `id`.
Verify the returned identity against the intended selection.

If a path was reused, use the original provider ID from your records.
Do not assign the replacement project's ID to an earlier selection.
Set the verified monitor ID in `native_id`.
Set a verified group ID in `config.gitlabGroupId` as a positive JSON number.
For a path-only stop, add its verified ID to `config.projectExclusions.nativeIds`.
Remove only that path from `config.projectExclusions.unresolvedPaths`.

Keep `stopReceipts` and all other configuration fields unchanged.
The migration removes the empty `unresolvedPaths` field.
For a stop that has no receipt, stop the project again before you remove its retained work.
For duplicate monitors, reconcile their review settings under the previous release before this upgrade.
Preserve both rows until that reconciliation is complete.

**Remove an abandoned selection only after both checks pass.**
First, obtain provider proof that the selected project or group was deleted, or its installation is gone.
A provider can also confirm that the selected path never identified a real project.
A missing credential, a 403, or a 404 alone does not prove deletion.

Second, use reliable provider records to identify the original project ID.
For a real project, use that provider ID as `native_id` in the commands below.
Do not use a database row ID or another project's ID.

Use SQL `NULL` only when provider proof establishes that the selection never identified a real project.
An empty local mirror does not establish that proof.

Keep every role stopped while you act on that result.
The commands refuse if the workspace retains a repository item for that provider ID, even after a rename.
They also refuse if the workspace retains a repository item at the selected path.
Removed items still count.
Other projects and conversation items do not block removal.

An unknown source origin prevents removal.
For a real project, an unresolved repository item also prevents removal.
If a real project's original ID is unknown, preserve the selection until a provider administrator can identify it.

If both checks pass, remove only the reported selection.
With psql, set the variables below to the values in the row report.
For a monitor, use its `row_id` as `monitor_id`:

```sql
DELETE FROM repository_to_monitor m
WHERE m.id = :monitor_id AND m.workspace_id = :workspace_id
    AND (:native_id IS NULL OR :native_id > 0) AND (m.native_id IS NULL OR m.native_id = :native_id)
    AND EXISTS (SELECT 1 FROM connection c JOIN identity_provider p
        ON p.type = c.kind AND p.server_url = c.config->>'serverUrl'
        WHERE c.workspace_id = m.workspace_id AND c.kind IN ('GITHUB','GITLAB'))
    AND NOT EXISTS (
        SELECT 1 FROM item i LEFT JOIN repository r ON r.id = i.source_id
        LEFT JOIN identity_provider p ON p.id = r.provider_id
        WHERE i.workspace_id = m.workspace_id AND i.kind = 'scm.repository' AND i.parent_id IS NULL
            AND ((r.id IS NULL AND :native_id IS NOT NULL) OR (
                (r.native_id = :native_id OR lower(r.name_with_owner) = lower(m.name_with_owner))
                AND EXISTS (SELECT 1 FROM connection c WHERE c.workspace_id = m.workspace_id
                    AND c.kind = p.type AND c.config->>'serverUrl' = p.server_url)
            ))
    );
```

For a path-only stop, use its `row_id` as `connection_id` and its `selection` as `stopped_path`:

```sql
UPDATE connection c SET config = jsonb_set(c.config, '{projectExclusions,unresolvedPaths}', (
    SELECT COALESCE(jsonb_agg(value), '[]')
    FROM jsonb_array_elements(c.config#>'{projectExclusions,unresolvedPaths}') stops(value)
    WHERE value <> to_jsonb(:'stopped_path'::text)
))
WHERE c.id = :connection_id AND c.workspace_id = :workspace_id AND c.kind = 'GITLAB'
    AND (:native_id IS NULL OR :native_id > 0)
    AND EXISTS (SELECT 1 FROM identity_provider p
        WHERE p.type = c.kind AND p.server_url = c.config->>'serverUrl')
    AND NOT EXISTS (
        SELECT 1 FROM item i LEFT JOIN repository r ON r.id = i.source_id
        LEFT JOIN identity_provider p ON p.id = r.provider_id
        WHERE i.workspace_id = c.workspace_id AND i.kind = 'scm.repository' AND i.parent_id IS NULL
            AND ((r.id IS NULL AND :native_id IS NOT NULL) OR (
                p.type = c.kind AND p.server_url = c.config->>'serverUrl'
                AND (r.native_id = :native_id OR lower(r.name_with_owner) = lower(:'stopped_path'))
            ))
    );
```

For an abandoned group connection, erase its empty workspace through instance administration before the upgrade.
After a migration attempt, restore the backup first.
Start the previous release to erase that empty workspace.
Stop every role before you retry the upgrade.
Do not fabricate a group ID.

Check that the command changes exactly one row.
If it changes no row, preserve the selection and check its reported identity and retained items.
Repair its ID, or use the existing workspace erasure procedure after the required approval and backup.
Do not remove retained work solely to pass the migration.

Run the report again after each repair or removal.
Retry the migration only when the report returns `[]`.
Restart the upgraded roles only after it completes.
