#### 🔴 Stop every role before correcting unknown issue comment counts

An omitted provider comment count used to be stored as zero. The migration makes issue comment counts nullable and changes existing issue zeros to unknown. It preserves positive counts and every pull request count. Counts show as unknown until the next backfill.

1. Stop every runtime role and take a verified database backup.
2. Run the upgraded migration job.
3. Start only the upgraded roles. The previous server maps the count to a primitive and cannot read the corrected rows.
4. Ask the administrators of each affected workspace to run a backfill.
   In the workspace's integration settings, they select its GitHub or GitLab connection and select **Run backfill** ([Repair activity history](https://docs.hephaestus.build/admin/activity-history-repair#run-the-repair)).
   Until it finishes, these issues show an unknown comment count.
   After it finishes, request a new review for work whose evidence was incomplete. Existing results are not rewritten.

To roll back, stop every role and restore the verified backup from before this migration before starting the previous server. Do not apply a reverse update that turns unknown counts into zero.
