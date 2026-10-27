---
"hephaestus": minor
---

The server now stops within seconds during an upgrade instead of waiting 20 seconds for open live streams; browsers reconnect to the new server and catch up.
A stopping worker now waits for its whole drain, running Heph turns included, before it closes its database connections. Before, the drain phase ended after a fixed 6 minutes.
The server's stop grace is now 45 seconds in the Helm chart and in Compose, and the Compose worker's is 7 minutes.
**Operators:** if you raised `HEPHAESTUS_WORKER_DRAIN_TIMEOUT` in Compose, set the worker's `stop_grace_period` to at least the drain plus 2 minutes.
