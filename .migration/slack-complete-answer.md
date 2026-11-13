#### 🔴 Stop every role before replacing live Slack replies

Stop all server replicas and keep a verified pre-upgrade database backup before applying this migration. Do not roll old and new server versions together. The migration moves the existing Slack outcomes into the outbox and removes the entry delivery and stream fields. It cannot be reversed; restore the pre-upgrade backup to recover the previous schema.

Existing in-flight replies whose Slack effect is unknown remain unconfirmed and are not posted again. The full answer remains in the app. New Slack replies are posted once their complete answer passes admission. Use **Stop answer** in the app to stop an unfinished answer.

The `mentor_slack_reply_total` metric is removed. Use `action_ended_total{kind="SLACK_POST"}` and the outbox alerts instead. Old in-flight actions ended unconfirmed by the migration do not run a new feedback receipt; their feedback remains unconfirmed.
