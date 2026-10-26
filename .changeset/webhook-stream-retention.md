---
"hephaestus": minor
---

Webhook streams now keep deliveries for 7 days instead of 180 (`HEPHAESTUS_WEBHOOK_STREAM_MAX_AGE`, and `webhook.stream.maxAge` in the Helm chart), so a busy stream stays far below its size limit. A stream at its size limit deletes its oldest message on every publish. A new alert, `HephaestusWebhookStreamNearByteBound`, fires when a stream stays above half of its size limit for an hour.

**Operators:** an existing stream keeps its 180 days until you apply the new limit once, and the webhook receiver logs `limit change withheld` at each start. The migration guide gives the steps.
