---
"hephaestus": patch
---

GitHub deliveries that failed while the server or its database was down are now redelivered after the outage. Before, the server asked GitHub only for the failed deliveries of the past two hours. Now a run reads back to the previous run, and a server's first run reads back 71 hours, as long as GitHub keeps deliveries. A server stopped from Friday evening to Monday morning thus loses no GitHub event. `github.webhook.unrecovered` now counts an event for two hours after its last failed attempt.
