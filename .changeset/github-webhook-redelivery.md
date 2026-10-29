---
"hephaestus": minor
---

GitHub events that the webhook receiver could not accept, for example while the message broker was slow or restarting, are now redelivered automatically. Every 10 minutes the server reads deliveries of the past two hours and asks GitHub for up to five redeliveries on the [documented schedule](https://docs.hephaestus.build/admin/webhook-ingestion-operations#failed-github-deliveries). The new `github.webhook.unrecovered` metric counts GitHub events that failed every attempt, and the example alert rules include it.
