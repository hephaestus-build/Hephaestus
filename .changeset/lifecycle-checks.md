---
"hephaestus": patch
---

The database now refuses a workspace, erasure request, evidence copy, product feedback report, issue, release check, model call permit or review run in a state that Hephaestus never writes. If an existing row has such a state, the upgrade stops before it changes anything and names the check.
