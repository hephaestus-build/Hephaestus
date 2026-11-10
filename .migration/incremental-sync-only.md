#### 🔴 GitHub synchronization is incremental

Remove `HEPHAESTUS_SYNC_INCREMENTAL_ENABLED` and `hephaestus.integration.github.sync.incremental-sync-enabled` from your configuration.

The update watermark still includes the configured `HEPHAESTUS_SYNC_INCREMENTAL_BUFFER` overlap. The first sync still uses `hephaestus.sync.timeframe-days`. Historical backfill continues to read older work with its own checkpoints and limits.
