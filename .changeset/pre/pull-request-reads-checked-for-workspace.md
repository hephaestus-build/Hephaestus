---
"hephaestus": patch
---

The server's check that a database read stays inside one workspace now inspects more reads of pull requests and of the people and teams asked to review them. Reads that sync and review need across workspaces are named one by one. A read of a pull request by its id still relies, as before, on the code that found that pull request within the workspace.
