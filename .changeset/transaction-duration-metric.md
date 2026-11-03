---
"hephaestus": patch
---

A new metric, `database_transaction_duration_seconds`, shows how long each database transaction holds a connection, for each place in the server that opens one. Use it to find what makes requests wait for a database connection.
