---
"hephaestus": minor
---

The small Kubernetes profile bounds PostgreSQL buffers and query memory, and sets server, worker, and migration connection pools separately. A worker surge fits within the 80-connection budget with maintenance and operator reserves. The PostgreSQL process and resident backup sidecar reserve their existing memory limits, so their memory cannot exceed the request. Lower ceilings require separate loaded qualification.

Optional chart values `postgresql.parameters` and each role's `databasePool` control the settings. The chart rejects invalid pools and an explicit database connection ceiling that cannot hold the configured roles and one worker surge.

**Operators:** Recalculate the PostgreSQL connection budget for custom worker replicas or pool settings before upgrading. Ensure node memory has room for the full PostgreSQL and backup requests. Set the role pools and `postgresql.parameters.max_connections` together.
