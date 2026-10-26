#### 🔴 Shorten the webhook streams to 7 days

Webhook streams now keep deliveries for 7 days.
A stream that already exists keeps its earlier 180 days, because startup does not delete stored messages on its own.
The webhook receiver then logs `Stream github limit change withheld because it would delete stored messages` at each start.

**Operators:** do these steps once, on each install whose streams existed before this release.

1. Check that `webhook.stream.consumer.pending` and `webhook.stream.consumer.ack.pending` are near zero.
   The messages that the new age deletes were then delivered already.
2. Allow the update for one start of the webhook receiver:
   - Docker Compose: set `HEPHAESTUS_WEBHOOK_STREAM_ALLOW_DESTRUCTIVE_LIMIT_UPDATES=true`.
     If you set `HEPHAESTUS_WEBHOOK_STREAM_MAX_AGE=180d`, change it to `7d` or remove it.
     Then restart the webhook receiver.
   - Helm chart: run `helm upgrade` with `--set webhook.stream.allowDestructiveLimitUpdates=true`.
     If you set `webhook.stream.maxAge: 180d`, change it to `7d` or remove it.
3. Look for `Reconciled JetStream stream limits and DELETED stored messages` in the log of the webhook receiver.
   The broker deletes the old messages before it answers. How long that takes on a large stream on a slow disk is unmeasured.
   If the log reports a timeout, raise `HEPHAESTUS_WEBHOOK_STREAM_LIMIT_UPDATE_TIMEOUT` (chart: `webhook.stream.limitUpdateTimeout`) and start the receiver again.
4. Turn the flag back off: `false` in the environment file, or `webhook.stream.allowDestructiveLimitUpdates=false` on the next `helm upgrade`.
