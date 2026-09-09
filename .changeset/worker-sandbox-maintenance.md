---
"hephaestus": patch
---

Worker-only deployments now reclaim idle mentor sessions and orphaned sandbox resources periodically. Stalled writes to a mentor runtime are now interrupted by the configured timeout, even while Docker cleanup is waiting for a response. Generating the API contract no longer runs sandbox cleanup against the local Docker daemon.
