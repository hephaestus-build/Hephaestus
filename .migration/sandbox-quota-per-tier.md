#### 🔴 Remove the hand-set sandbox quota values

The chart now derives the sandbox quotas from `worker.replicas`, `worker.reviews` and `worker.hephTurns`: one quota for review sandboxes and one for the Heph host and turn sandboxes. It no longer reads `worker.sandbox.quota.pods`, `worker.sandbox.quota.cpu` or `worker.sandbox.quota.memory`, and its values schema refuses them.

Before you upgrade, delete these three keys from your values file. Keep `worker.sandbox.quota.enabled`. To run more reviews or Heph turns at once, raise `worker.reviews` or `worker.hephTurns`: the quotas follow.

If you manage the quotas yourself (`worker.sandbox.quota.enabled: false`), a quota that selects the review PriorityClass, or that has no scope, bounds how many reviews a worker claims.
