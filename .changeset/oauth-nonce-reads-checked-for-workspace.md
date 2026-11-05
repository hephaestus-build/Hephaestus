---
"hephaestus": patch
---

The server's check that a database read stays inside one workspace now covers the OAuth sign-in state store. The two reads that must reach every workspace are named: the check that a new sign-in state is unused, and the removal of expired ones.
