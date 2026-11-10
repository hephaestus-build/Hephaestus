---
"hephaestus": minor
---

Every conversation turn now runs in the conversation loop, a new Deployment that the chart runs beside the server. Any server serves any turn, and a server update no longer removes the tools of a running turn. The loop runs two replicas on different nodes where the cluster has them, and one replica in the single-node profile. `conversationLoop.replicas`, `conversationLoop.turns` and `conversationLoop.resources` set it. The turn count is now the count of the whole installation, not of each server. The single-node profile admits 31 turns, and 29 with backups on. The metrics `mentor_host_ready` and `mentor_host_turns_active` are removed.

**Operators:** Stop every role for this upgrade. Before the migration, add `HEPHAESTUS_CONVERSATION_LOOP_SECRET` to the release Secret: run `hephaestus-keys.sh` from the release, or set the output of `openssl rand -hex 32`. Move `worker.conversationTurns` to `conversationLoop.turns`, because the chart refuses the old value. `HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX` is removed.
