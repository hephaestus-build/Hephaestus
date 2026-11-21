---
"hephaestus": minor
---

The bundled PostgreSQL no longer runs out of memory when its connections fill up. It now starts with JIT compilation off, and the chart sizes its memory from its memory settings and its connection limit. `postgresql.resources` memory is now `auto`: the `small` profile gets 3 GiB instead of 2 GiB, and its automatic conversation turns drop from 11 to 8, or from 7 to 4 with backups on. The memory settings of the `small` profile are now chart defaults, and an install without a profile gets `shared_buffers` of 256MB instead of a quarter of the limit.

**Operators:** If your values set `postgresql.resources.requests.memory` or `limits.memory`, set them to `auto`, or keep a limit at or above the size that [PostgreSQL memory](https://docs.hephaestus.build/admin/sizing#postgresql-memory) derives. The chart refuses a smaller limit. If you turned JIT off with `ALTER SYSTEM`, reset it after the upgrade.
