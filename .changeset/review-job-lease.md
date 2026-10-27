---
"hephaestus": patch
---

Each practice review that a worker runs now holds a lease, which the worker renews with its heartbeat. When a worker stops, the server requeues its reviews once their leases expire. After the server starts, and after its checks stalled, it waits one heartbeat interval before it requeues anything, so a worker that stalled with the database keeps its reviews. A review that runs while you upgrade to 1.0 is requeued once. No action is needed.
