---
"hephaestus": patch
---

The scheduled GitHub and GitLab syncs now sync at most four workspaces at the same time, so they no longer take all database connections of the server, and pages and API requests no longer wait while they run. With many workspaces, a scheduled sync takes longer to finish.
