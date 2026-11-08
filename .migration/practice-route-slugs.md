#### 🔴 Check practice identifiers before upgrading

New practices cannot use the occupied first segments of practice API routes. The creation APIs and editor enforce the same rule. Group names are not reserved by this change. Existing practice identifiers are immutable and are not renamed.

Run this read-only preflight with a database role that can read all workspaces. Both counts must be zero before upgrading. Do not proceed if a collision exists: prepare a reviewed data migration or refuse the upgrade.

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL row_security = off;
WITH reserved(slug) AS (VALUES
    ('definition-options'),
    ('feedback'),
    ('feedback-modes'),
    ('items'),
    ('practice-script-needs'),
    ('releases'),
    ('results'),
    ('review-batches'),
    ('review-context'),
    ('review-requests'),
    ('review-schedules'),
    ('review-settings'),
    ('reviewed'),
    ('reviews'),
    ('scans'),
    ('standings'),
    ('tiles'),
    ('workspace-overview')
)
SELECT 'workspace_practices' AS scope, count(*) AS collisions
FROM practice JOIN reserved USING (slug)
UNION ALL
SELECT 'catalog_practices', count(*)
FROM catalog_entry JOIN reserved USING (slug)
WHERE entry_type = 'PRACTICE';
ROLLBACK;
```

The published input patterns in `server/openapi.yaml` define the rule. Future static practice APIs must use a separate namespace so they cannot take an identifier that this release accepts.
