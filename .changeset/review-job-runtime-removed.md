---
"hephaestus": minor
---

Workers no longer run practice reviews themselves. Every review runs in its own agent sandbox, so the worker's review Job runtime is removed.

**Operators:** remove `AGENT_POLL_INTERVAL` and `AGENT_CLAIM_BATCH_SIZE` from your configuration. They have no effect. These metrics are no longer exported: `sandbox_containers_active`, `sandbox_executions_total`, `sandbox_execution_duration_seconds`, `sandbox_start_failures_total`, `sandbox_review_starved_seconds`, `sandbox_cleanup_failures_total`, `sandbox_reconciler_*`, `worker_capacity_*`, `worker_drain_active`, `agent_job_drained_total` and `agent_job_execution_duration_seconds`. Remove dashboards and alerts of your own that read them.
