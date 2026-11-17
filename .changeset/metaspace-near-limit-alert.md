---
"hephaestus": minor
---

The new `HephaestusMetaspaceNearLimit` alert warns when a server's Metaspace stays above 80 % of its maximum for 10 minutes, so you can act before a slow growth stops the server with `OutOfMemoryError: Metaspace`. The heap and container memory alerts do not see Metaspace. A server that runs out of Metaspace soon after each start fires `HephaestusServerRestarting` instead, and its runbook row now points to this alert. No operator action is needed: a Prometheus that already loads the chart's rule file picks it up.
