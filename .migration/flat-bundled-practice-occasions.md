#### 🔴 Practice definitions and observation outcomes use a new contract

This is a coordinated pre-1.0 cutover, not a rolling upgrade. The old practice occasion list and
observation assessment axes are removed. Do not run old workers or API clients against the new server.

1. Stop review scheduling and let reviews finish, or cancel them. Stop every runtime role.
2. Create a database backup and verify that you can restore it into a separate database.
3. Only if observations serve an actual research or audit purpose, export the original observation
   fields while every role is still stopped. The upgrade removes them, they cannot be rebuilt from the
   new outcome, and a rotating database backup is for recovering this instance, not an archive:

   ```sql
   \copy (SELECT id, workspace_id, practice_id, practice_revision_id, agent_job_id, assessment_status,
          presence, assessment, severity, observed_at FROM observation) TO 'observation-axes.csv' CSV HEADER
   ```

   Record beside it the Hephaestus release and the last applied Liquibase changeset it was taken from.
   Keep the export encrypted in private custody with access limited to that purpose, and retain and
   erase it under the retention and erasure rules your organization already applies to that purpose,
   not your backup rotation. Do not publish it. This step sets no new policy and leaves research
   archives you have already published unchanged.
4. Convert custom catalogue files and API payloads to the flat fields `signals`, `evidenceRequirements`, `reviewWhen`,
   `subject`, and optional `precondition`. The precondition's explanation is `skipReason`.
   `reviewWhen` is an object of descriptor-owned state selections, not a shared draft flag.
   For non-draft pull or merge requests use `{"draftStatus":["NOT_DRAFT"]}`; use `{}` for no state
   restriction. Issues have no draft state, documents expose active or archived, and conversations
   expose no lifecycle selection. The migration preserves the previous pull-request draft restriction
   without adding it to other work types.
   Describe the positive standard in `criteria`; remove matrix-based instructions.
5. Update integrations to submit `outcome`: `MET`, `NOT_MET`, `NOT_APPLICABLE`, or `UNDETERMINED`.
   Supply severity exactly for `NOT_MET`. Retain the appropriate evidence warrants.
6. Deploy matching server, review runtime, webapp, and browser extension versions and let the forward
   migration finish. Before restarting reviews, update the criteria of persisted practices to describe
   the positive standard, without matrix instructions: instance customizations first, then workspace
   copies through catalogue updates, then custom practices in the editor. Then verify practice editing,
   one review, its recorded result, and delivery.

The migration preserves historical criteria and does not invent missing historical definitions.
Unsupported or ambiguous existing definitions must not be silently flattened: the migration refuses an
occasion without a known subject or a true or false draft choice, and any stored definition field the
new contract does not read. If the migration rejects existing data, keep the instance stopped and
inspect the reported prerequisite failure.
Do not disable its checks or change a released migration.

**Recovery:** Stop all roles and restore the verified pre-upgrade database backup together with the
previous application images. The removed fields cannot be reconstructed reliably from new results;
there is no automatic reverse conversion. Old positive labels are not evidence that the complete new
practice standard was met.
