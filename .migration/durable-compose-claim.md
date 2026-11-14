#### 🔴 Drain legacy review compositions

Before this first upgrade, stop the old servers and let their review drain finish. Keep PostgreSQL running.
Do not start target servers until no `REVIEW_COMPOSE` run has a live lease.
Follow [Drain legacy review compositions](https://docs.hephaestus.build/admin/upgrade#drain-legacy-review-compositions).
An old server held each claim decision in its own memory, so the target cannot read it. Later rolling upgrades keep the same decision across server replicas.

Set `server.databasePool.maximumPoolSize` to `auto`, or raise an explicit override to the chart's validated floor.
Keep `postgresql.parameters.max_connections` at the derived setting or a larger validated value.
The existing budget helper now reserves three connections for each concurrent capture and one unpooled boot connection for each possible server process. It remains the only connection-budget source.
The derived `max_connections` for `cluster.yaml` rises from 240 to 300, and PostgreSQL restarts to apply it. `small.yaml` stays at 120.
This step does not change the gateway Service. A later step can remove its client-IP affinity.
