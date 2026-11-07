---
"hephaestus": patch
---

The server's check that a database read stays inside one workspace now inspects more reads of commits, labels, milestones and repository collaborators. Reads and writes that sync and monitoring need across workspaces are named one by one. A read by the id of a repository or commit still relies, as before, on the code that found it within the workspace.
