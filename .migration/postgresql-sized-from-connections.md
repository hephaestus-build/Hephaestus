#### 🔴 Make room for the bundled PostgreSQL's derived memory

- **When:** before the window
- **Action:** Render the target chart with your values, and check that one node has room for the PostgreSQL memory request that it shows.

The chart now derives the memory of the bundled PostgreSQL from its memory settings and `max_connections` ([PostgreSQL memory](https://docs.hephaestus.build/admin/sizing#postgresql-memory)). The request and the limit are equal. `small` and `dev` now request 4020 MiB, and `cluster` requests 9251 MiB for its 300 connections.

The upgrade replaces the PostgreSQL Pod. If no node has room for the new request, the new Pod stays `Pending` and the database is down until you free room. Before the window, compare the rendered request with the allocatable memory of your nodes, less the requests already on them. A lower `max_connections` or a smaller pool lowers the request.

If the render fails with `postgresql.resources.limits.memory (…) needs at least …`, remove `postgresql.resources.requests.memory` and `postgresql.resources.limits.memory` from your values, or set them to `auto`. A larger explicit limit is accepted. A request above the limit is refused.

If your values set `sandbox.budget` for a node that also runs workloads outside the release, such as the cluster's DNS and Gateway, state their requests in `sandbox.budget.outsideRelease` ([Node reserve](https://docs.hephaestus.build/admin/sizing#node-reserve)).

The chart starts PostgreSQL with `jit = off`, which takes precedence over a `jit` setting made with `ALTER SYSTEM`. If you made one, you can remove it after the upgrade as the database superuser: `ALTER SYSTEM RESET jit; SELECT pg_reload_conf();`.
