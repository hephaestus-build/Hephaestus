---
"hephaestus": minor
---

Workers and the server now coordinate only through the database. The WebSocket channel between them is gone, so a worker no longer opens a connection to the server, and a server restart no longer disconnects the workers. A cancelled review stops its model calls at once; its worker stops the sandbox within one heartbeat. Each worker signs its sandboxes' credentials with a key that it keeps in memory.

**Operators:** delete `HEPHAESTUS_WORKER_REGISTRATION_TOKEN`, `HEPHAESTUS_HUB_URL` and any `hephaestus.worker.hub.token.*`, `hephaestus.worker.control.*` or `hephaestus.worker.heartbeat.*` settings, which Hephaestus no longer reads. Move alerts and dashboards off the removed metrics `worker.control.*`, `worker.heartbeats.*`, `worker.hub.*`, `worker.token.exchange` and `worker.jwt.verify`; `attempt.token.verify` replaces the last one. A practice review that still runs when you upgrade is requeued by the server and spends no retry.

If the server cannot resume an in-flight review, it ends the claim and closes the review after a fresh timeout, with a recorded upgrade failure. Admitted results stay available. This maintenance also runs when review execution is disabled.
