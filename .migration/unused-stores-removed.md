#### 🔴 Stop every role for this upgrade, which drops unused stores

This release drops stores that the application no longer reads or writes:

- the `outline_document_event` table, the Outline document event log, which holds Outline editor ids;
- the `consent_notice` table (each consent decision keeps its notice version, and the wording of every version is in the release that published it);
- the `product_survey_submission` table, after the upgrade copies each submission that has no participation record yet;
- the `review_job.idempotency_key` column and its index `uk_review_job_active_key`;
- the `supports_reasoning` column of `llm_model` and `workspace_llm_model`;
- the `workspace_slug_history.redirect_expires_at` column, because rename redirects do not expire;
- the `review_snapshot_id` and `review_snapshot_digest` columns of `issue`;
- the `research_opted_out` and `source` columns of `slack_participant_consent`, after the upgrade copies each research opt-out of a linked Slack user into the consent decisions of the account.

A runtime role of the previous release fails when it uses these objects.
Stop every runtime role of the previous release before you start this release.
Back up the database first.
The upgrade deletes the rows of these stores.
A rollback of the migration stops and asks for the backup.
