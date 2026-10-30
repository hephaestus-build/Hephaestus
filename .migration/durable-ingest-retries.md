#### 🔴 Remove obsolete webhook retry settings

Remove `hephaestus.integration.consumer.max-ack-pending` and `hephaestus.integration.consumer.poison.max-redeliver` from your configuration. The consumer now enforces one outstanding event per workspace and stream. Temporary failures retry without a delivery limit. Apply the database migration before starting the server. Dead-letter payloads are stored in PostgreSQL and must be included in your backup and access controls.
