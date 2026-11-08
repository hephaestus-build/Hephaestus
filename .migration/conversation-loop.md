#### 🔴 Stop every role for the conversation loop

Every conversation turn now runs in the conversation loop, a Deployment beside the workers.
A worker of an earlier release cannot serve the turns of this release.

**Operators:**

1. Stop every role, as phase 1 of [Stopped schema upgrades](https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades) describes.
2. Before phase 2, add `HEPHAESTUS_CONVERSATION_LOOP_SECRET` to the release Secret.
   Run `hephaestus-keys.sh` from this release with your usual options. It adds only the keys that the Secret lacks.
   Or set the key yourself to 64 lowercase hexadecimal characters, the output of `openssl rand -hex 32`.
3. In your values, move `worker.conversationTurns` to `conversationLoop.turns`. The chart refuses `worker.conversationTurns`.
   The count is now the turns of the whole installation, not of each worker.
4. If you run a worker outside the chart, remove `HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX`.
   Set `HEPHAESTUS_CONVERSATION_LOOP_SECRET` and `HEPHAESTUS_MENTOR_TURN_CAPACITY` on each worker. A worker without the key does not start.
5. Continue with phase 2 of the procedure.
