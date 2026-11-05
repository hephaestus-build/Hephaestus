---
"hephaestus": minor
---

With `postgresql.metrics.enabled`, four new alerts watch PostgreSQL: a deadlock, a transaction that has been open for more than 5 minutes, a table that holds more than 20 % dead rows for an hour, and a transaction ID age of more than 1 billion. The last one is critical, because PostgreSQL stops writes at about 2 billion. The production operations runbook gives the response to each. Without the exporter, the alerts stay silent.
