---
"hephaestus": minor
---

You can now see whether the outbox keeps up. New gauges count the actions that have not ended (`action_backlog`), and show how long the oldest due action of each kind has waited (`action_oldest_due_seconds`). `HephaestusOutboxStalled` fires when an action has been due for more than 15 minutes, and `HephaestusOutboxUncertain` fires when an action ends uncertain, such as an email whose sending cannot be confirmed. The runbook has a row for each.
