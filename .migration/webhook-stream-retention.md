#### 🔴 Shorten the webhook streams to 7 days

Webhook streams now keep deliveries for 7 days (`HEPHAESTUS_WEBHOOK_STREAM_MAX_AGE`).
A stream that already exists keeps its earlier 180 days, because startup does not delete stored messages on its own.
The webhook receiver then logs `Stream github limit change withheld because it would delete stored messages` at each start.

**Operators:** do these steps once, on each install whose streams existed before this release.

1. Check that `webhook.stream.consumer.pending` and `webhook.stream.consumer.ack.pending` are near zero.
   The messages that the new age deletes were then delivered already.
2. If you set `HEPHAESTUS_WEBHOOK_STREAM_MAX_AGE=180d` in your environment file, change it to `7d` or remove it.
3. Set `HEPHAESTUS_WEBHOOK_STREAM_ALLOW_DESTRUCTIVE_LIMIT_UPDATES=true` and restart the webhook receiver.
   A full stream can take several minutes to shrink.
   If the log reports a timeout, raise `HEPHAESTUS_WEBHOOK_STREAM_LIMIT_UPDATE_TIMEOUT` and restart again.
4. Look for `Reconciled JetStream stream limits and DELETED stored messages` in the log of the webhook receiver.
5. Set `HEPHAESTUS_WEBHOOK_STREAM_ALLOW_DESTRUCTIVE_LIMIT_UPDATES` back to `false`.
