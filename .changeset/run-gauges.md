---
"hephaestus": minor
---

The run gauges show why work waits. For each kind of run and each band, the server now reports the runs that have not ended by state, the runs that can start now, how long the oldest one has waited, and how long a claim waited after its run became due. A run that waits on a spent model budget or for sandbox room is counted as held, so a paused workspace no longer reads like an idle instance. `HephaestusAgentQueueStalled` now names the band that waits.

**Operators:** the meters `agent_queue_depth`, `agent_queue_running`, `agent_queue_held`, `agent_queue_oldest_age_seconds`, `agent_queue_health_sampler_failures_total` and `agent_job_claim_latency_seconds` are gone. `agent_run_runs`, `agent_run_due`, `agent_run_oldest_due_age_seconds`, `agent_run_sampler_failure_total` and `agent_run_claim_wait_seconds` replace them. The bundled alert and dashboard read the new names. Change your own queries, alerts and dashboards that read an old name; the migration guide maps each one.
