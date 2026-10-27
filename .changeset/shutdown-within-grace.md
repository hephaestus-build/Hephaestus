---
"hephaestus": patch
---

The server now stops within seconds during an upgrade instead of waiting 20 seconds for open live streams; browsers reconnect to the new server and catch up.
A stopping worker now waits for its whole drain before it closes its database connections, also when the drain is longer than 6 minutes.
`SHUTDOWN_TIMEOUT` no longer applies to the worker: `HEPHAESTUS_WORKER_DRAIN_TIMEOUT` alone sets how long it may take to stop.
