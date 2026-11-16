---
"hephaestus": patch
---

The server now stops waiting for a database lock after 10 seconds, so a request that would have waited forever fails instead of hanging. Provider events, the results that a review admits while it runs, and model calls that wait too long are retried instead of being reported as failures. A provider event that keeps waiting is logged as a warning. If the final result of a review attempt waits too long, that attempt runs again, which is logged as a warning. Database migrations wait for locks without a limit. Data erasure waits for running review captures without a limit; if an erasure step waits too long for a lock, it is retried once and then stops with a recorded reason that an admin can resume. An `options` parameter in the database URL replaces this limit.
