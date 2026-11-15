---
"hephaestus": minor
---

A server no longer runs out of Git cache space while it reviews or loads a conversation's repository. The Git cache volume now keeps room for one snapshot for each review and each conversation load that a server runs at once. The server's eviction budget is the rest. Before, the budget was the whole volume, so running reviews could fill it and Kubernetes evicted the server.

**Operators:** a new value, `server.gitCache.snapshotLimit` (default `2Gi`), sets the most disk that one snapshot may use. A snapshot is a review's checkout with its history, a conversation's context load, or a scan. A larger repository is refused whole. Before, the server allowed 8 GiB. A review or a conversation can no longer use a repository of 2 to 8 GiB until you raise `snapshotLimit`, and the chart still renders. Check your largest repositories before the upgrade.

The chart refuses a `server.gitCache.sizeLimit` that leaves less than 1Gi beside those snapshots. Its message names the size that fits. If you set a small `sizeLimit`, raise it, and the server's `ephemeral-storage` limit with it. Or lower `snapshotLimit`.
