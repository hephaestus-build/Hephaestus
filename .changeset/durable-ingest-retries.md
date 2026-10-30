---
"hephaestus": minor
---

Webhook events now wait through database and provider outages without exhausting a retry budget. Events that cannot be parsed have a durable replay record. Workspace admins can replay their workspace's events through the API.

**Operators:** Remove `hephaestus.integration.consumer.max-ack-pending` and `hephaestus.integration.consumer.poison.max-redeliver` from your configuration. Each workspace and stream now processes one event at a time, with unlimited retries for temporary failures. Apply the database migration before the server starts.
