---
"hephaestus": patch
---

The new `HephaestusMetaspaceNearLimit` alert warns when a server's Metaspace stays above 80 % of its maximum for 10 minutes. Before, a server that ran out of Metaspace stopped with `OutOfMemoryError: Metaspace` and restarted in a loop with no earlier warning, because the heap and container memory alerts do not see Metaspace. No operator action is needed: a Prometheus that already loads the chart's rule file picks it up, and the runbook says what to check.
