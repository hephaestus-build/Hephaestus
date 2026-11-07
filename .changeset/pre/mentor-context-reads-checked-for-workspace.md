---
"hephaestus": patch
---

The server's check that a database read stays inside one workspace now inspects the reads behind the merge readiness context that a review receives. The four reads of one pull request that review needs are named one by one: each reads a pull request that was first found within the workspace.
