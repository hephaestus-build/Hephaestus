#### 🔴 Check database connection limits for review recovery

Before the upgrade, render the target release's chart with your normal values.
Set `server.databasePool.maximumPoolSize` to `auto`, or raise a fixed value to the minimum that the chart reports.
Review preparation now holds a capture lease, a preparation lock and a publication transaction.
The pool also reserves a connection for boot work.

If you set `postgresql.parameters.max_connections`, check it against the target chart's complete rollout budget.
Raise it before the new servers start if the chart reports that it is too small.
The automatic value includes the old servers while they drain, the replacement servers, and five dedicated listeners per server.
[Database connections](https://docs.hephaestus.build/admin/sizing#database-connections) gives the calculation.
