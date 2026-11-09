---
"hephaestus": minor
---

A review whose worker stops is now picked up again by any server replica within one lease, and never by two. Each review attempt holds a lease on the database clock, and only the worker that runs the attempt renews it. A review that waits for a sandbox, a drain, or another replica is offered fairly: requested and event-driven reviews before scheduled ones, then the workspace with the fewest running reviews, then the oldest. A scheduled review that waits ten minutes is offered with the requested ones, so it never waits behind them for long.

**Operators:** the retry limit is now `hephaestus.agent.capacity.review-retries`, still set by `AGENT_MAX_RETRIES`. `hephaestus.agent.max-retries` is gone. The `HephaestusReviewWorkerLost` alert now reads `agent_run_reaped_total`, and the new `HephaestusAgentRunReaperFailing` alert fires when the reaper keeps failing to take back an expired lease. The `agent.job.orphan.*` and `agent.job.zombie.reaped` metrics are gone. Stop every role for this upgrade; see the migration guide.
