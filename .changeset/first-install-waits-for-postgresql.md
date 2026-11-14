---
"hephaestus": patch
---

On a fresh install the server no longer restarts while PostgreSQL starts and the migration creates its database role. It now waits until the database accepts its connection and the migration is complete, within the same migration wait (`hephaestus.database.migration-wait`, 10 minutes by default), and logs why it waits. If the database still refuses the connection after that time, the server stops with the database's reason and tells you to check the database address and credentials. The startup probe now allows two minutes more than the wait, so the server reports this before Kubernetes restarts it.
