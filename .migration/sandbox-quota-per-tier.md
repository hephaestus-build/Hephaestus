#### 🔴 Remove the hand-set sandbox quota values

The chart now derives the sandbox quotas from `worker.replicas`, `worker.reviews` and `worker.hephTurns`: one quota for review sandboxes and one for the Heph host and turn sandboxes. It no longer reads `worker.sandbox.quota.pods`, `worker.sandbox.quota.cpu` or `worker.sandbox.quota.memory`, and its values schema refuses them.

Before you upgrade, delete these three keys from your values file. Keep `worker.sandbox.quota.enabled`. To run more reviews or Heph turns at once, raise `worker.reviews` or `worker.hephTurns`: the quotas follow.

The quotas hold exactly what the sandboxes request, and a quota also counts a RuntimeClass's Pod overhead. If `worker.runtime.runtimeClassName` names a class with an `overhead.podFixed`, such as gVisor or Kata on some clusters, set `worker.runtime.podOverhead.cpuMillis` and `worker.runtime.podOverhead.memoryMi` to it. Otherwise the quota refuses the worker's isolation probe, and the worker starts no sandbox.

`priorityClasses.reviews` and `priorityClasses.conversations` must name different classes. The chart refuses to render if they are the same.

If you manage the quotas yourself (`worker.sandbox.quota.enabled: false`), a quota that selects the review PriorityClass, or that has no scope, bounds how many reviews a worker claims.
