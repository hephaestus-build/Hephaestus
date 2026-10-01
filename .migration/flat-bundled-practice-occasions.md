#### 🔴 Practice definitions and observation outcomes use a new contract

This is a coordinated pre-1.0 cutover, not a rolling upgrade. The old practice occasion list and
observation assessment axes are removed. Do not run old workers or API clients against the new server.

1. Stop review scheduling and let reviews finish, or cancel them. Stop every runtime role.
2. Create a database backup and verify that you can restore it into a separate database.
3. Convert custom catalogue files and API payloads to the flat fields `signals`, `evidenceRequirements`, `onDrafts`,
   `subject`, and optional `precondition`. The precondition's explanation is `skipReason`.
   Describe the positive standard in `criteria`; remove matrix-based instructions.
4. Update integrations to submit `outcome`: `MET`, `NOT_MET`, `NOT_APPLICABLE`, or `UNDETERMINED`.
   Supply severity exactly for `NOT_MET`. Retain the appropriate evidence warrants.
5. Deploy matching server, review runtime, and webapp versions. Allow the forward migration to finish
   before restarting reviews. Update the criteria of persisted custom practices in the new editor to
   describe the positive standard, without matrix instructions. Then verify practice editing, one
   review, its recorded result, and delivery.

The migration preserves historical criteria and does not invent missing historical definitions.
Unsupported or ambiguous existing definitions must not be silently flattened. If the migration
rejects existing data, keep the instance stopped and inspect the reported prerequisite failure.
Do not disable its checks or change a released migration.

**Recovery:** Stop all roles and restore the verified pre-upgrade database backup together with the
previous application images. The removed fields cannot be reconstructed reliably from new results;
there is no automatic reverse conversion. Preserve original research records separately. Old positive
labels are not evidence that the complete new practice standard was met.
