---
"hephaestus": patch
---

Historical backfill compares a repository's stored pull requests with the provider without stopping on the workspace check. That stop could end a workspace's scheduled GitHub backfill, so later repositories stayed unprocessed, and it could leave a manual backfill's history check undone.
