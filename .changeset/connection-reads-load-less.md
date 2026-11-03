---
"hephaestus": patch
---

The server reads less from the database for each request and each synced event. Reading a connection no longer also loads its workspace and the workspace's list of monitored repositories.
