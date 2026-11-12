#### 🔴 Route provider webhooks to the server

The server now receives provider webhooks. The chart has no webhook Deployment, and it refuses `webhook.replicas` and `webhook.resources`.

**Affected**: every Kubernetes installation, and every installation that starts the application image outside the chart with the `webhook` Spring profile or `HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED`.

**Operators:**

1. Stop every role, as phase 1 of [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades) describes.
   The 1.0 upgrade stops every role in any case.
2. In your values, remove `webhook.replicas` and `webhook.resources`. Keep `webhook.stream`.
   The webhook Pod's memory is free for the server. Keep the server's defaults or add it to `server.resources`.
3. Outside the chart, stop the webhook container. Remove `HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED` and the `webhook` Spring profile.
   Route `/webhooks` and every path below it to the server.
4. Replace `HephaestusWebhookReceiverDown` and `HephaestusWebhookReceiverUnready` in your own alert routing with `HephaestusServerDown` and `HephaestusServerUnready`.
5. Remove filters on `runtime.role` from your log queries. Logs now identify the one application through its service metadata.
6. Continue with phase 2 of the procedure.
   The upgrade deletes the webhook Deployment, its Service, its ServiceAccount, its disruption budget and its network policy.
