---
"hephaestus": patch
---

Expired data exports are now removed even while one of them is in use. The hourly removal works in small batches and retries a busy export on its next run, so one busy export no longer keeps the expired exports of every other account.
