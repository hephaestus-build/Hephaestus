---
"hephaestus": patch
---

The bundled PostgreSQL no longer restarts every connection when a health probe times out. Before, a probe that took longer than one second left a process behind that PostgreSQL took for a crashed backend, so it ended all sessions and recovered again, in a loop. The PostgreSQL image now starts under a small init process that cleans up such processes, and each probe waits up to 10 seconds. Two new alerts watch the database: `HephaestusPostgresDown` fires when PostgreSQL or its exporter does not answer for 5 minutes, and `HephaestusPostgresExporterMissing` fires when the exporter that the chart deploys has had no scrape target for 5 minutes.
