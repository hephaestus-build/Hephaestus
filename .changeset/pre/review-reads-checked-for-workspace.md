---
"hephaestus": patch
---

The server's check that a database read stays inside one workspace now inspects more reads of pull request reviews, review comments and review threads. Reads that sync needs across workspaces are named one by one. A read of a pull request's reviews by the pull request's id still relies, as before, on the code that found that pull request within the workspace.
