#### 🔴 Rename scheduled sync settings

Before upgrading, rename `config.monitoring` to `config.sync` in the site file. Keep `runOnStartup`, `timeframeDays`, `syncCron`, and `cooldownMinutes` and their current values.

Replace these explicit server environment variables if you set them outside the chart:

| Removed variable | Use instead |
| --- | --- |
| `MONITORING_RUN_ON_STARTUP` | `SYNC_RUN_ON_STARTUP` |
| `MONITORING_TIMEFRAME` | `SYNC_TIMEFRAME_DAYS` |
| `MONITORING_SYNC_CRON` | `SYNC_CRON` |
| `MONITORING_SYNC_COOLDOWN_IN_MINUTES` | `SYNC_COOLDOWN_MINUTES` |
| `MONITORING_BACKFILL_ENABLED` | `SYNC_BACKFILL_ENABLED` |
| `MONITORING_BACKFILL_BATCH_SIZE` | `SYNC_BACKFILL_BATCH_SIZE` |
| `MONITORING_BACKFILL_RATE_LIMIT_THRESHOLD` | `SYNC_BACKFILL_RATE_LIMIT_THRESHOLD` |
| `MONITORING_BACKFILL_INTERVAL_SECONDS` | `SYNC_BACKFILL_INTERVAL_SECONDS` |

The application property names under `hephaestus.sync` stay the same. The old chart group is rejected, and the server no longer reads the old environment names.
