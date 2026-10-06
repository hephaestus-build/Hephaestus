---
"hephaestus": patch
---

The worker now starts with a larger database connection pool, which leaves room for several reviews to prepare code evidence at the same time. The other roles keep their pool size. Set `HIKARI_MAXIMUM_POOL_SIZE` to choose a different size for the worker.
