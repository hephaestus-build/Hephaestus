---
"hephaestus": patch
---

Each practice review that a worker runs now holds a lease, which the worker renews with its heartbeat. When a worker stops, the server requeues its reviews once their leases expire. After the server starts, and after its checks stalled, it waits one heartbeat interval before it requeues anything, so a worker that stalled with the database keeps its reviews. The upgrade to 0.95.0 recovers a running review without a lease as a drain, without spending a retry. Follow **Workers no longer connect to the server** in [this release's migration guide](https://github.com/hephaestus-build/Hephaestus/blob/main/MIGRATION.md).
