---
"hephaestus": patch
---

A practice review no longer spends a retry for a reason that is not its own. When a worker's drain stops it, for example during an upgrade, it runs again on another worker without spending a retry; after 3 drains, the next one cancels it. On Kubernetes, when the sandbox quota has no room for its sandbox, the review waits in the queue instead of failing after two minutes; after about an hour without room, it is cancelled with "Hephaestus had no room to run this review in time." A review whose sandbox is larger than the whole quota is cancelled at once with "The sandbox quota is smaller than one review sandbox." The metrics `agent_job_drained_total` and `sandbox_start_failures_total` count these. The worker needs `get` and `list` on `events` and `resourcequotas` in the sandbox namespace, which the chart's Role grants; a Role of your own needs them too.
