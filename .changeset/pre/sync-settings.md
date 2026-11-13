---
"hephaestus": minor
---

Scheduled repository sync settings now use `config.sync` in the chart. The schedule, lookback, startup behavior, and cooldown keep their values. The server's explicit sync and backfill environment names now start with `SYNC_`.

**Operators:** Before upgrading, move the site-file group `config.monitoring` to `config.sync`. Keep its four child settings. Replace any explicit `MONITORING_*` sync or backfill variables with the names in the migration guide. The old chart group and environment bindings are removed.
