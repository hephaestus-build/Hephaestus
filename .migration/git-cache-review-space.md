#### 🔴 The Git cache volume keeps room for review checkouts

- **When:** before the window
- **Action:** Render the target chart with your values. If it refuses `server.gitCache.sizeLimit`, raise it to the size that the message names, with the server's `ephemeral-storage` limit, or lower `server.gitCache.snapshotLimit`. For a repository whose checkout and history take more than 2Gi, raise `server.gitCache.snapshotLimit` and `sizeLimit` with it.

The chart now keeps one snapshot (`server.gitCache.snapshotLimit`, default `2Gi`) of the Git cache volume for each review and each conversation load that a server runs at once. The server's eviction budget is the rest of `server.gitCache.sizeLimit`. The chart refuses values that leave less than 1Gi.

Before you upgrade, check your values. With the sandboxes on, a server runs as many reviews as `sandbox.reviewMemory` divided by one review sandbox's memory request: five with the small profile. It also prepares two conversation loads at once while conversation turns are on. With the small profile and the defaults, the chart refuses a `sizeLimit` of 10Gi, and the message names the size that fits. Raise `server.gitCache.sizeLimit`, for example to 20Gi, and the server's `ephemeral-storage` limit with it. Or lower `server.gitCache.snapshotLimit` if your repositories are small.

Also check your largest repositories. Until now the chart did not set the limit for one snapshot, so the server's own limit of 8Gi applied. With the chart's 2Gi, a review or a conversation load of a repository whose checkout and history take more than 2Gi is refused, although the chart renders. For such a repository, raise `server.gitCache.snapshotLimit` to its size, and `server.gitCache.sizeLimit` with it.
