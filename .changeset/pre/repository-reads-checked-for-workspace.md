---
"hephaestus": patch
---

The server's check that a database read stays inside one workspace now inspects more reads of repositories. Reads that sync and monitoring need across workspaces are named one by one. A read of a repository by its id still relies, as before, on the code that found that repository within the workspace.
