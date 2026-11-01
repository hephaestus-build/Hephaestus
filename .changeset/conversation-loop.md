---
"hephaestus": minor
---

Hephaestus answers in a conversation from its own conversation loop. The loop holds no model or provider credential and no workspace data at rest. Each answer gets a fresh tools sandbox only when it reads a repository or runs a command. The sandbox has no network and no credential, and it is removed when the answer ends or when what it may read changes. A worker no longer starts a conversation host or keeps a pool of turn sandboxes.

**Operators:** The Helm chart adds the `conversation-loop` Deployment, its ServiceAccount and a gateway Service in the release namespace. The worker may now create TokenReviews (a ClusterRole with only `tokenreviews: create`), because it checks the loop's ServiceAccount token. `worker.conversationTurns` now counts the turns of each loop replica, and `worker.conversationLoop.replicas` sets the replicas. The worker no longer reads `HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX`.
