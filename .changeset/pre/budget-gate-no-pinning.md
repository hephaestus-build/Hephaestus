---
"hephaestus": patch
---

Model calls of one workspace that start at the same moment no longer stall the server while it checks the workspace's model budget. Before, such calls could hold every request thread, so that other requests waited until they failed with a database connection timeout.
