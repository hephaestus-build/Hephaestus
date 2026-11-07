---
"hephaestus": patch
---

A single-node upgrade no longer rolls back because a worker was still draining. The `small` profile now drains a worker for at most 10 minutes instead of 30, so the drain, the shutdown and the new worker's start fit in the documented 20-minute Helm timeout. A review that outlasts the drain runs again. It spends no retry, but it counts toward the limit on drains. To keep a longer drain, set `worker.drainTimeoutSeconds` and raise `--timeout` with it.
