#### 🔴 Drain the webhook streams before the upgrade

Webhook deliveries now wait in the provider event inbox in PostgreSQL. The upgrade does not read the NATS webhook streams, and it drops the old dead-letter records, which held no payload.

**Affected**: every installation that receives provider webhooks.

**Operators:**

1. Replay the dead letters that you want to keep, with the version that you run now ([Replay a failed webhook event](https://docs.hephaestus.build/admin/webhook-replay)).
   A dead letter that you do not replay is not carried over. The routine sync repairs what it can.
2. Just before you stop the roles, wait until `webhook_stream_consumer_pending` and `webhook_stream_consumer_ack_pending` are 0 for each stream.
   An event that is still in a stream when the roles stop is not handled.
3. Stop every role, as phase 1 of [Database maintenance](https://docs.hephaestus.build/admin/maintenance#database-maintenance) describes.
   While no server runs, GitHub redelivers the failed deliveries on its bounded redelivery schedule after the upgrade, Slack retries, and the routine sync repairs GitLab and Outline.
4. In your values, remove `webhook` (its last setting was `webhook.stream`) and `config.consumerInactiveThreshold`.
   Outside the chart, remove the `HEPHAESTUS_WEBHOOK_STREAM_*` variables and `HEPHAESTUS_INTEGRATION_CONSUMER_INACTIVE_THRESHOLD`.
5. In your own alert routing, remove `HephaestusStreamPollStale`, `HephaestusStreamPollMissing`, `HephaestusStreamBacklog`, `HephaestusStreamConsumerStopped`, `HephaestusWebhookStreamPastAgeBound` and `HephaestusWebhookRetentionBelowAgeBound`.
   Add `HephaestusProviderEventsWaiting` and `HephaestusProviderEventBacklogUnsampled`.
   Replace `integration_consumer_nak_total` with `integration_consumer_retry_total` in your own rules and dashboards.
6. Continue with phase 2 of the procedure.
   The inbox keeps handled deliveries for 3 days and dead letters for 30 days: plan PostgreSQL space for 3 days of webhook traffic.
   The migration drops the old inbox and dead-letter tables. To go back to the earlier version, restore a PostgreSQL backup taken before the upgrade ([Backup and restore](https://docs.hephaestus.build/admin/backup-restore)).
