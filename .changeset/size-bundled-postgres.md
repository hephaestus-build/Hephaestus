---
"hephaestus": minor
---

The bundled PostgreSQL now sizes its memory settings and its connection limit from its container's memory limit, 4 GiB by default (`POSTGRES_MEM_LIMIT`), and records statement statistics with `pg_stat_statements`. At the default it allows 256 connections instead of 100, enough for the old and the new roles of a rolling update. The webhook server uses a database pool of 2 connections instead of 20, which leaves more connections for the other roles. **Operators:** the PostgreSQL limit is new; if the host has less free memory, lower `POSTGRES_MEM_LIMIT` before you upgrade.
