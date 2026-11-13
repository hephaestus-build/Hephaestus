---
"hephaestus": patch
---

The server now stops waiting for a database lock after 10 seconds, so a request that would have waited forever fails with a logged, retryable error. Busy provider events and review results are retried instead of being reported as failures. Database migrations and data erasure keep unbounded waits.
