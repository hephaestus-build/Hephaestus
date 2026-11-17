---
"hephaestus": patch
---

The server no longer records which branches and tags each monitored repository had at its last fetch. Nothing reads that record since a pull request's freshness depends only on its own commits.

**Operators:** Stop every runtime role before this upgrade's migration, which drops two columns that the previous release still writes. See the migration guide.
