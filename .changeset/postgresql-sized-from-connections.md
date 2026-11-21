---
"hephaestus": minor
---

The bundled PostgreSQL now has memory for every connection that it allows, starts with JIT compilation off, and leaves the node a real margin at the upgrade peak. As a result, a 16 GiB node runs 3 automatic conversation turns at once, or 2 with backups on: down from 11 and 7 with `small.yaml`, and from 9 and 6 on the one host. With backups, database metrics and the bundled monitoring stack all on, it runs 1.

- `postgresql.resources` memory is now `auto`: the chart sizes it from the memory settings and the connection limit, with 26 MiB for each connection. That figure is deliberately cautious until it is measured on a production installation: a database that runs out of memory ends every session. The `small` and `dev` profiles get 4020 MiB, up from 2 GiB and 1 GiB, and `cluster` gets 9251 MiB for its 300 connections, up from 2 GiB.
- Before the chart derives turns, it keeps 5 % of `sandbox.budget` and the new `sandbox.budget.outsideRelease`, the requests of the workloads outside the release on the same node, free. The `small` profile states the one-host platform's requests there.
- With sandboxes, a backup never preempts a conversation or the upgrade: when the node is full, it waits for room, and a preemption no longer uses up its retry. The scheduled backup Jobs and pgBackRest's server beside PostgreSQL now request 256 MiB, down from 512 MiB, the stanza Job of each upgrade 64 MiB, and the restore keeps 512 MiB in the new `postgresql.backup.restore.resources`.
- The memory settings of the `small` profile are now chart defaults, so `cluster` and installs without a profile get `shared_buffers` of 256MB, `maintenance_work_mem` of 64MB and `effective_cache_size` of 1GB, which the image derived from the limit before.

**Operators:** Before the upgrade, check that one node has room for the new PostgreSQL request, and that your values hold no memory limit below it. [PostgreSQL memory](https://docs.hephaestus.build/admin/sizing#postgresql-memory) gives the size.
