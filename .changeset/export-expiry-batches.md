---
"hephaestus": patch
---

The hourly cleanup of expired data exports now clears their data in small batches and retries a busy export on its next run. One busy export no longer keeps the expired data of every other account.
