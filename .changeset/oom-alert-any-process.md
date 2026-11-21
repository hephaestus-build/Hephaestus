---
"hephaestus": patch
---

An out-of-memory kill of one process inside a running container, such as a PostgreSQL backend, now raises an alert. Before, `HephaestusContainerOomKilled` fired only when the kill ended the container, so a killed database backend, which makes PostgreSQL end every session, went unreported. `HephaestusContainerOomKilled` now also reads cAdvisor's per-container OOM counter, and the new `HephaestusNodeOomKill` reads the kernel's count of OOM kills on each node from node-exporter.
