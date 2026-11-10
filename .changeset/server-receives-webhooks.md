---
"hephaestus": minor
---

The server now receives provider webhooks itself, so an installation runs one application Deployment. There is no webhook Deployment to size, update or watch, and its alerts now watch the server: `HephaestusServerDown` and `HephaestusServerUnready` replace `HephaestusWebhookReceiverDown` and `HephaestusWebhookReceiverUnready`. While no server runs, providers cannot deliver: GitHub redelivers the deliveries that failed in the past two hours, Slack retries, and the routine sync repairs GitLab and Outline.

**Operators:** the chart refuses `webhook.replicas` and `webhook.resources`. Remove them before you upgrade. `webhook.stream` stays. Outside the chart, `HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED` and the `webhook` Spring profile are gone: route `/webhooks` to the server.
