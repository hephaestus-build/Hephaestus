---
"hephaestus": patch
---

`HEPHAESTUS_WORKER_CAPACITY_REVIEW_MAX` is gone. A worker no longer limits its reviews to one less than its processors: `worker.reviews` alone decides, and the chart refuses a value that the worker's memory limit cannot hold. If you set that variable directly, set `worker.reviews` instead.
