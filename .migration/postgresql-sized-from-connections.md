#### 🔴 Let the chart size the bundled PostgreSQL's memory

- **When:** before the window, after the upgrade
- **Action:** Set `postgresql.resources` memory to `auto` in your values. After the upgrade, reset a `jit` setting made with `ALTER SYSTEM`.

The chart now derives the memory of the bundled PostgreSQL from its memory settings and `max_connections` ([PostgreSQL memory](https://docs.hephaestus.build/admin/sizing#postgresql-memory)). It refuses an explicit `postgresql.resources.limits.memory` below that size.

Before the window, render the target chart with your values. If it fails with `postgresql.resources.limits.memory (…) needs at least …`, remove `postgresql.resources.requests.memory` and `postgresql.resources.limits.memory` from your values, or set them to `auto`. A larger explicit limit is accepted.

The chart starts PostgreSQL with `jit = off`. A command-line setting takes precedence over `postgresql.auto.conf`, so a `jit` setting made with `ALTER SYSTEM` no longer has an effect. After the upgrade, remove it as the database superuser: `ALTER SYSTEM RESET jit; SELECT pg_reload_conf();`. Then `SHOW jit` answers `off`, and `SELECT source FROM pg_settings WHERE name = 'jit'` answers `command line`.
