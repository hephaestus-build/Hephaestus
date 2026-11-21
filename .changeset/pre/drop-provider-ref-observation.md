---
"hephaestus": patch
---

The server no longer records which branches and tags each monitored repository had at its last fetch. Nothing reads that record since a pull request's freshness depends only on its own commits. New GitLab webhooks no longer subscribe to tag push events, which nothing reads. A webhook registered earlier still delivers them, and the server acknowledges and drops them.

**Operators:** Stop every runtime role before this upgrade's migration, which drops two columns that the previous release still writes. See the migration guide.
