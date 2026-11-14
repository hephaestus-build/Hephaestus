---
"hephaestus": patch
---

The server now stops waiting for a database lock after 10 seconds, so a request that would have waited forever fails instead of hanging. Provider events, review results and model calls that wait too long are retried instead of being reported as failures, and a provider event that keeps waiting is logged as a warning. Database migrations wait for locks without a limit. Data erasure waits for running review captures without a limit; if an erasure step waits too long for a lock, it is retried once and then stops with a recorded reason that an admin can resume. An `options` parameter in the database URL replaces this limit.
