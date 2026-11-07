---
"hephaestus": minor
---

Repositories that outgrow a worker's disk now slow captures before they cost running reviews. Sandbox input archives moved from the fixed 1 GiB `/tmp` to the Git cache volume, and when that volume passes 80 percent of `gitCache.sizeLimit`, the server and worker delete the mirrors they used least recently, except those used in the last 10 minutes. Each deleted mirror costs a full fetch at its next capture. Review checkouts that fill the volume alone can still evict the Pod, so two new alerts watch the volume and evictions: load the rule file of the release to get them.

**Operators:** `server.gitCache.sizeLimit` and `worker.gitCache.sizeLimit` must now be a whole number of `Mi` or `Gi`. Change a value such as `20G` or `1.5Gi` before you upgrade.
