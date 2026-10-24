---
"hephaestus": patch
---

A cancelled review now stops at the worker's next liveness heartbeats, even when the cancel comes from a server that has no control connection to that worker. A review attempt that was requeued can no longer record its late result over the attempt that replaced it. Heph replies are no longer restarted when the clocks of the server and the worker differ, because the reply lease now uses the database clock.
