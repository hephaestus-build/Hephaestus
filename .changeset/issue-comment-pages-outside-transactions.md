---
"hephaestus": patch
---

The GitHub issue and pull request syncs and the historical backfill no longer hold a database connection while they ask GitHub for the rest of a long comment conversation, including while they wait out a rate limit.
