---
"hephaestus": patch
---

The bundled PostgreSQL no longer restarts every connection when a health probe times out. Before, a probe that took longer than one second left a process behind. PostgreSQL took it for a crashed backend, so it ended all sessions and recovered again, in a loop. The PostgreSQL image now starts under a small init process that cleans up such processes. Each probe now waits up to 10 seconds.

Two new alerts watch the database. `HephaestusPostgresDown` fires when PostgreSQL or its exporter does not answer for 5 minutes. `HephaestusPostgresExporterMissing` fires when the chart's exporter has had no scrape target for 5 minutes.
