#### 🔴 Classify unrecorded copies of "Confirm the outcome before closing the issue"

The upgrade withdraws this practice from automated review and switches off, at server startup, every
workspace copy whose persisted `source_curated_slug` names `issue-closed-with-unmet-outcome`. A copy with no
recorded source is left untouched and stays active: it was made and edited before Hephaestus recorded where
copies came from, and nothing stored distinguishes it from a practice a workspace wrote itself. A matching
slug is not proof of either, so the upgrade does not decide.

After the upgrade, list the candidates (read-only):

```sql
SELECT p.workspace_id, p.id, p.slug, p.name, p.autonomy
FROM practice p
WHERE p.slug = 'issue-closed-with-unmet-outcome'
  AND p.source_curated_slug IS NULL;
```

For each row, confirm with that workspace's administrator whether it is an old copy of the catalog practice
or the workspace's own practice. For an old copy, have the administrator set it to **Off** under
**Workspace administration → Practices → Review**, which records the change in the configuration audit
log; do not update the row directly. A practice the workspace wrote itself needs no action. Until a copy is
switched off it keeps being reviewed on issue close and can record results from the issue as it stands
after the close. Results recorded before the upgrade are unchanged.
