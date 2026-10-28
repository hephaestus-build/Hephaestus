---
"hephaestus": minor
---

Workers and the server now coordinate only through the database. The WebSocket channel between them is gone, so a worker no longer needs to reach the server, and a server restart no longer disconnects the workers. A cancelled review stops its model calls at once; its worker stops the sandbox within one heartbeat. Each worker signs its sandboxes' credentials with a key that it keeps in memory.

You can delete `HEPHAESTUS_WORKER_REGISTRATION_TOKEN`, `HEPHAESTUS_HUB_URL` and any `hephaestus.worker.hub.token.*`, `hephaestus.worker.control.*` or `hephaestus.worker.heartbeat.*` settings. Hephaestus no longer reads them. The Helm chart and the k3s installer no longer set them.
