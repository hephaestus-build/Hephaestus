#### 🔴 Stop every role before moving conversation turn leases to agent runs

The leases of conversation turns move from `mentor_turn` to the `agent_run` table.
The previous release cannot read or renew a turn lease in the new table.

1. Stop the server, worker, webhook and conversation loop roles.
2. Back up the database.
3. Upgrade. The migration gives each turn its run.
   A turn that a loop still held when it stopped is a lost attempt: after the upgrade, the server records its usage and queues it again, or fails it after its retry.
4. If you set `hephaestus.mentor.turn.capacity` yourself, set `hephaestus.agent.capacity.turns` instead. The chart and `HEPHAESTUS_MENTOR_TURN_CAPACITY` need no change.
   `hephaestus.mentor.turn.lease` is now `hephaestus.agent.capacity.turn-lease-ttl`. `hephaestus.mentor.turn.lease-reaper-delay` is gone: the run reaper runs every `hephaestus.agent.capacity.lease-renewal`.
5. Start the roles.
