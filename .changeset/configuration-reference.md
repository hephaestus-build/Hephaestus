---
"hephaestus": patch
---

The admin guide has a configuration reference that lists every variable you can set, with the setting it controls, its default, whether it is required, and what it does. Three Compose settings were ignored and now take effect: `ITEM_EVENT_LEDGER_PENDING_RETRY_AFTER`, `ITEM_EVENT_LEDGER_PENDING_LAPSE_AFTER` and `ITEM_EVENT_LEDGER_SWEEP_BATCH_SIZE` replace the `SIGNAL_LEDGER_*` names, and `PRACTICE_REVIEW_BACKFILL_MAX_ITEMS` replaces `PRACTICE_REVIEW_BACKFILL_MAX_ARTIFACTS`. To apply a value that you set under an old name, rename it in `.env`. `PRACTICE_REVIEW_PROGRESS_FOOTER` and `HEPHAESTUS_RELEASE_PIN_SKIP` are gone from the templates because nothing reads them.
