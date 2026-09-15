---
"hephaestus": patch
---

A development e2e setup with a local SCM simulator can review merge requests again: the trusted
Git fetch now runs in the worker's own network namespace when the simulation origin is configured,
so it reaches the simulator on loopback exactly as the application's own provider calls do.
Production fetches are unchanged.
