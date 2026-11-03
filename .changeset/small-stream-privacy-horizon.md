---
"hephaestus": minor
---

On the `small` profile, Slack and Outline deliveries now stay in the broker for 72 hours at most, not seven days. They carry personal content that an erasure request cannot reach in a stream. A new alert, `HephaestusWebhookStreamPastAgeBound`, reports a stream that holds deliveries past its setting.

**Operators:** on an existing `small` installation, set `webhook.stream.allowDestructiveLimitUpdates` to `true` for one upgrade. See the migration guide.
