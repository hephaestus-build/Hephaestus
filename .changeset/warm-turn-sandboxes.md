---
"hephaestus": patch
---

The first Heph answer after a restart on Kubernetes no longer waits for a sandbox to start: the worker starts its empty turn sandboxes as soon as its isolation check passes.
