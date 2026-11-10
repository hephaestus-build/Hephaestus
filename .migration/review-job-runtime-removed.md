#### 🔴 Remove the review poll settings and the review Job metrics

Workers no longer run practice reviews. Each review runs in its own agent sandbox.
Remove `AGENT_POLL_INTERVAL` and `AGENT_CLAIM_BATCH_SIZE` from your configuration. They have no effect.
Remove dashboards and alerts of your own that read `sandbox_containers_active`, `sandbox_executions_total`, `sandbox_execution_duration_seconds`, `sandbox_start_failures_total`, `sandbox_review_starved_seconds`, `sandbox_cleanup_failures_total`, `sandbox_reconciler_*`, `worker_capacity_*`, `worker_drain_active`, `agent_job_drained_total` or `agent_job_execution_duration_seconds`. These metrics are no longer exported.
