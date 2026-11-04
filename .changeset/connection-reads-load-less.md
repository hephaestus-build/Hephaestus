---
"hephaestus": patch
---

The server reads less from the database for each request and for each provider call during a sync. Reading a connection no longer also loads its workspace and the workspace's list of monitored repositories.
