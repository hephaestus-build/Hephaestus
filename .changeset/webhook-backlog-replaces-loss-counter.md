---
"hephaestus": minor
---

Webhook monitoring no longer reports false losses for filtered consumers on shared streams. It now
reports each stream's current pending and unacknowledged work instead.

**Operators:** replace alerts on the removed `webhook.stream.unacknowledged.deletions` and
`webhook.stream.unacknowledged.gap` metrics with sustained backlog alerts. See the webhook ingestion
operations guide for the new metrics and their limits.
