---
"hephaestus": minor
---

Webhook events now wait through database and provider outages without exhausting a retry budget. Permanent failures and exhausted unknown failures have a durable replay record. Workspace admins can replay their workspace's events through the API.

**Operators:** Remove `hephaestus.integration.consumer.max-ack-pending` from your configuration. Each workspace and stream now processes one event at a time, with unlimited retries for temporary failures. Set retry base and maximum delays to at least one second. Unknown failures use `hephaestus.integration.consumer.poison.max-redeliver` (default 10). Apply the database migration before the server starts. Replay reads the original event from NATS and must finish within the stream retention period.
