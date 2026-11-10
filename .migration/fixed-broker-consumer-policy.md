#### 🔴 Remove broker consumer settings

**Who is affected:** Operators whose values contain `config.durableConsumerName` or `config.consumerInactiveThreshold`.

**Action:** Remove both keys from every values file. The chart refuses them, including their old default values. The fixed consumer prefix is `hephaestus`; inactive consumers expire after 30 days.

If the old consumer prefix was `hephaestus`, the upgrade keeps its consumer names and positions. No consumer rename is required. A different inactive-expiry setting becomes 30 days; plan downtime within that bound.

If the old prefix was different, complete these steps before you upgrade:

1. Stop the webhook receiver, which publishes to this deployment's provider streams: set `webhook.replicas` to `0` and upgrade with your current chart version. Keep the server consumers running.
2. Use an authenticated NATS CLI context for this instance. Run `nats stream report` and `nats consumer info STREAM CONSUMER` for each stream and consumer owned by the old prefix. Record their names, configurations, stream sequences and ACK positions.
3. Wait until each old consumer has zero pending and zero unacknowledged messages. Refuse the upgrade if you cannot reach and verify that state.
4. Stop the server and workers. Back up PostgreSQL and the broker state as the production operations runbook specifies.
5. Remove the retired chart keys and upgrade, still with `webhook.replicas` at `0`, so that the new server attaches the `hephaestus` consumers before anything publishes.
6. Verify that the new consumers are attached and have no pending deliveries. Delete only the recorded old consumers. Do not delete their streams. Then restore `webhook.replicas` and upgrade again.

A new consumer starts at new messages, so changing its prefix before draining would skip pending work. The chart does not rename consumers or transfer their ACK positions. For recovery after longer downtime, use [the broker recovery procedure](https://docs.hephaestus.build/admin/production-operations-runbook#choose-the-broker-recovery-strategy-before-starting-consumers).
