#### 🔴 Reserve room for rolling server updates

Remove `server.rolloutStrategy` from your site values. The chart refuses this retired key and always uses `RollingUpdate`.

Use the target release's profile. The default is two steady servers; `small` keeps one. Budget space and database connections for every draining server beside every replacement, as [Sandbox capacity budget](https://docs.hephaestus.build/admin/helm-chart#sandbox-capacity-budget) requires. Set `server.databasePool.maximumPoolSize` to `auto`, or recalculate an explicit count against the runtime holder floor. Remove a copied `postgresql.parameters.max_connections: 80` override: the target small profile derives 120. It keeps the PostgreSQL memory request and limit at 2 GiB. [Database connections](https://docs.hephaestus.build/admin/sizing#database-connections) gives the pool and client breakdown.

Finish the review drain before the upgrade peak, as [Sandbox capacity during upgrade](https://docs.hephaestus.build/admin/upgrade#sandbox-capacity-during-upgrade) requires. Start no further upgrade until all old server Pods have stopped. A schema-compatible release can then roll while a server remains ready.

The initial cutover to 1.0 and every incompatible schema still use the stopped procedure in [Upgrade to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0).
