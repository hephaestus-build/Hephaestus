---
"hephaestus": patch
---

The server's check that a database statement stays inside one workspace now covers the OAuth sign-in state store. Two statements are exempted by name: the read that checks that a new sign-in state is unused, and the daily deletion of expired sign-in states.
