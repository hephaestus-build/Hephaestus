---
"hephaestus": patch
---

Expired data exports now lose their download even while one of them is in use. The hourly expiry works in small batches and retries a busy export on its next run, so one busy export no longer keeps the downloads of every other account.
