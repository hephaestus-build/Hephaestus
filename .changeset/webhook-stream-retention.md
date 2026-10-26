---
"hephaestus": minor
---

Webhook streams now keep deliveries for 7 days instead of 180 (`HEPHAESTUS_WEBHOOK_STREAM_MAX_AGE`, and `webhook.stream.maxAge` in the Helm chart). A busy stream now stays far below its size limit, so the broker's disk use stays bounded, and the new alert `HephaestusWebhookStreamNearByteBound` warns you when a stream stays above half of that limit for an hour, before it would delete deliveries that were not read yet. This does not make a slow broker faster: that depends on the disk under it.

**Operators:** an existing stream keeps its 180 days until you apply the new limit once. The webhook receiver logs `limit change withheld` at each start until then. The migration guide gives the steps for Docker Compose and for the Helm chart, which now has `webhook.stream.allowDestructiveLimitUpdates` and `webhook.stream.limitUpdateTimeout`.
