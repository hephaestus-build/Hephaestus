#### 🔴 Conversations move to the conversation loop

The 1.0 chart runs conversation turns in the new `conversation-loop` Deployment. The workers no longer start a conversation host or turn sandboxes.

- The chart creates a ClusterRole and a ClusterRoleBinding that let the worker create TokenReviews. The account that runs `helm upgrade` must be allowed to create them.
- Remove `HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX` from your values. Set `worker.conversationTurns` per loop replica.
- Questions that wait during the upgrade start when the loop's first replica is ready.
