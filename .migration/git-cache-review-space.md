#### 🔴 The Git cache volume keeps room for review checkouts

The chart now keeps one snapshot (`server.gitCache.snapshotLimit`, default `2Gi`) of the Git cache volume for each review and each conversation load that a server runs at once. The server's eviction budget is the rest of `server.gitCache.sizeLimit`. The chart refuses values that leave less than 1Gi.

Before you upgrade, check your values. With the sandboxes on, a server runs as many reviews as `sandbox.reviewMemory` divided by one review sandbox's memory request: five with the small profile. It also prepares two conversation loads at once while conversation turns are on. With the small profile and the defaults, the chart refuses a `sizeLimit` of 10Gi, and the message names the size that fits. Raise `server.gitCache.sizeLimit`, for example to 20Gi, and the server's `ephemeral-storage` limit with it. Or lower `server.gitCache.snapshotLimit` if your repositories are small.
