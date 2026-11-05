---
"hephaestus": patch
---

The server's check that a database read stays inside one workspace now inspects more reads of issue and pull request comments. Reads that sync needs across workspaces are named one by one. A read of an issue's comments by the issue's id still relies, as before, on the code that found that issue within the workspace.
