#### 🔴 Change the queries that read the agent queue and claim meters

Change your own queries, alerts and dashboards that read the agent queue or claim meters to the agent run meters in the table.
The agent run gauges replace the queue gauges, and the claim wait of a run replaces the claim latency of a job.
The bundled alert `HephaestusAgentQueueStalled` and the **Overview** dashboard read the new names.
The table names the new meter of each old one:

| Old | New |
| --- | --- |
| `agent_queue_oldest_age_seconds` | `agent_run_oldest_due_age_seconds`, with the labels `kind` and `band` |
| `agent_queue_depth` | `agent_run_due`, with the labels `kind` and `band` |
| `agent_queue_running` | `agent_run_runs{state="leased"}` |
| `agent_queue_held` | `agent_run_runs{state="queued",hold_reason="budget"}` |
| `agent_queue_health_sampler_failures_total` | `agent_run_sampler_failure_total` |
| `agent_job_claim_latency_seconds` | `agent_run_claim_wait_seconds`, with the labels `kind` and `band` |

`agent_run_runs` has the labels `kind`, `band`, `state` and `hold_reason`.
`state` is `queued` or `leased`. `hold_reason` is `none`, `budget` or `sandbox_room`.
Sum over the labels that you do not need.
A held run is not due: `agent_run_due` and `agent_run_oldest_due_age_seconds` leave it out.

The claim wait counts from the time that the run became due.
Each claim of a returned run counts again from its new due time.
The old latency counted once, from the submission of the job.
Series with the old names stop at the upgrade.
