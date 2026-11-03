#### 🔴 Apply the 72-hour horizon to Slack and Outline on the small profile

The `small` profile kept Slack and Outline deliveries for seven days. These streams carry personal content, and an erasure request cannot reach inside them, so they now keep deliveries for 72 hours, as on every other profile.

A new installation has this at once. So does a stream that holds nothing older than 72 hours. On any other stream, the upgrade alone does not apply it: startup withholds a shorter age, because that deletes stored messages.

**The one action:** check that `webhook.stream.consumer.pending` and `webhook.stream.consumer.ack.pending` are near zero, set `webhook.stream.allowDestructiveLimitUpdates: true` for one upgrade, then set it back to `false`.
The flag applies to every stream. If your site values also lower a bound on another stream, that bound takes effect too, and it deletes what no longer fits.

**What shows that it is not in effect yet:** the alert `HephaestusWebhookStreamPastAgeBound`, and `webhook.stream.oldest.message.age` above `webhook.stream.age.bound` for `slack` or `outline`.

After this change, a stored event older than 72 hours can no longer be replayed: its replay returns `410`. An outage longer than 72 hours loses older Slack and Outline events from the stream:

- The nightly Slack history sync refills messages in active monitored channels. It also removes a stored message that a complete read of Slack no longer returns. The admin guide's data retention page, under *Messages deleted or edited in Slack*, lists the messages that it does not compare.
- The Outline sync restores each document's current state, and it removes deleted documents on a clean reconciliation pass.
