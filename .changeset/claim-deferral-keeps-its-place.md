---
"hephaestus": patch
---

A review that cannot start yet now keeps its place in the queue and is tried again every 5 seconds. This happens when review calls wait for its model pool, or when its review is being changed. Before, it was put back as new work every 2 seconds, so nothing showed how long it had waited. The new `agent_run_deferred_total` metric counts these waits by reason, and each wait writes an `agent.run.deferred` log record. `HephaestusAgentQueueStalled` now sees a review that waits this way for more than one hour.
