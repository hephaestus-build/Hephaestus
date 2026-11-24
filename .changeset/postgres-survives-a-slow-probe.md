---
"hephaestus": patch
---

The bundled PostgreSQL no longer restarts every connection when a health probe times out. Before, a probe that took longer than one second left a process behind that PostgreSQL took for a crashed backend, so it ended all sessions and recovered again, in a loop. The PostgreSQL image now starts under a small init process that cleans up such processes, and each probe waits up to 10 seconds. A new alert, `HephaestusPostgresDown`, fires when PostgreSQL does not answer its exporter for 5 minutes.
