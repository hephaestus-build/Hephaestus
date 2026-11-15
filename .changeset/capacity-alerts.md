---
"hephaestus": minor
---

Three new alerts warn you before capacity runs out. `HephaestusPostgresConnectionsHigh` fires when the clients of one PostgreSQL instance hold more than 90 % of the connections that ordinary roles can use for 15 minutes, before PostgreSQL refuses new connections. It reads the metrics of the database exporter, so it needs `postgresql.metrics.enabled`. `HephaestusServerRestarting` fires when a server container restarts three times in an hour, in any namespace. `HephaestusNodeMemoryLow` fires when a node has had less than 10 % of its RAM available for 15 minutes. These two read kube-state-metrics and node-exporter, which the chart's own monitoring stack does not run. No operator action is needed: a Prometheus that already loads the chart's rule file picks them up, and each has a row in the runbook.
