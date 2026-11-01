---
"hephaestus": patch
---

A worker that starts while the sandbox quota is full, for example during a rolling update, now waits for room for its isolation probe and stays not ready. It no longer exits and restarts. A probe that runs and fails still stops the worker.
