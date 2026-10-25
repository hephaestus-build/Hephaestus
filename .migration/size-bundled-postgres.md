#### 🔴 Leave room for the new PostgreSQL memory limit

The bundled Compose stacks now limit PostgreSQL to 4 GiB (`POSTGRES_MEM_LIMIT`).
The image sizes `shared_buffers` and its other memory settings from that limit when it starts.
Before this release, PostgreSQL had no limit and used PostgreSQL's small defaults.
The upgrade restarts PostgreSQL once to apply the limit.

**Operators:** do this step only if the host has less than 4 GiB free beside the other containers.

1. Before the upgrade, set `POSTGRES_MEM_LIMIT` in `.env` to the memory that PostgreSQL can use, for example `2g`.

See [PostgreSQL memory](https://docs.hephaestus.build/admin/sizing#postgresql-memory).
