---
"hephaestus": patch
---

The GitHub pull request sync and the historical backfill no longer hold a database connection while they ask GitHub for the rest of a long review thread list or the rest of a thread's comments, including while they wait out a rate limit.
