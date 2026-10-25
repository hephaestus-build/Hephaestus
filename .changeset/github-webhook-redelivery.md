---
"hephaestus": minor
---

GitHub events that the webhook receiver could not accept, for example while the message broker was slow or restarting, are now redelivered automatically. Every 10 minutes the server asks GitHub to send again each failed delivery of the past hour, up to three times. The new `github.webhook.unrecovered` metric counts GitHub events that failed every attempt, and the example alert rules include it.
