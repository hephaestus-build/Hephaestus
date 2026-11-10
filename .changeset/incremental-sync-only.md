---
"hephaestus": minor
---

GitHub synchronization always uses the buffered update watermark. The first sync still uses the configured timeframe, and historical backfill still reads older work.

**Operators:** Remove `HEPHAESTUS_SYNC_INCREMENTAL_ENABLED` and `hephaestus.integration.github.sync.incremental-sync-enabled` from your configuration.
