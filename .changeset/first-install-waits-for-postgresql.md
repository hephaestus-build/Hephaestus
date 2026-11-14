---
"hephaestus": patch
---

On a fresh install the server no longer restarts once while PostgreSQL starts. It now waits until the database accepts connections and the migration is complete, within the same migration wait (`hephaestus.database.migration-wait`, 10 minutes by default). If the database stays unreachable for that time, the server stops with a message that says so, instead of a connection error.
