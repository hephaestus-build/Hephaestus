#### 🔴 Git cache sizes are whole `Mi` or `Gi`

`server.gitCache.sizeLimit` and `worker.gitCache.sizeLimit` now also set the size at which a role deletes its least recently used Git mirrors, so the chart reads them as a number.

The chart refuses any other form, such as `20G` or `1.5Gi`.

**Action:** before you upgrade, write each value as a whole number of `Mi` or `Gi`, such as `20Gi`. Values from the shipped profiles need no change.
