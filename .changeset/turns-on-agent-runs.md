---
"hephaestus": minor
---

A stopped conversation answer keeps what Hephaestus wrote before the stop. Questions run in their conversation's order, including after two accounts are linked.

**Operators:** stop every role before you upgrade; see the migration guide. `hephaestus.mentor.turn.capacity` is now `hephaestus.agent.capacity.turns`, and the turn lease is `hephaestus.agent.capacity.turn-lease-ttl`. The chart's `conversationLoop.turns` and the `HEPHAESTUS_MENTOR_TURN_CAPACITY` variable are unchanged. `hephaestus.mentor.turn.lease`, `hephaestus.mentor.turn.lease-reaper-delay` and the metric `mentor_turn_lease_reaper_failure_total` are gone: the run reaper's `agent_run_reaper_failure_total` covers turns. The `worker_registry` table, the `hephaestus.agent.heartbeat-interval` setting, the metric `worker_liveness_heartbeat_failures_total` and the review queue's `activeWorkers` field are gone: an agent run's lease shows whether its worker lives.
