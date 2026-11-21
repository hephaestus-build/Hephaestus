---
"hephaestus": minor
---

The bundled PostgreSQL now has memory for every connection that it allows, and starts with JIT compilation off. The chart sizes its memory from its memory settings and its connection limit, and `postgresql.resources` memory is now `auto`. The `small` and `dev` profiles get 3200 MiB, up from 2 GiB and 1 GiB. The `cluster` profile gets 7 GiB, up from 2 GiB, for its 300 connections. On `small`, automatic conversation turns drop from 11 to 8, or from 7 to 4 with backups on. On the one host, they drop from 9 to 7, or from 6 to 3 with backups on. The memory settings of the `small` profile are now chart defaults, so `cluster` and installs without a profile get `shared_buffers` of 256MB, `maintenance_work_mem` of 64MB and `effective_cache_size` of 1GB, which the image derived from the limit before.

**Operators:** Before the upgrade, check that one node has room for the new PostgreSQL request, and that your values hold no memory limit below it. [PostgreSQL memory](https://docs.hephaestus.build/admin/sizing#postgresql-memory) gives the size.
