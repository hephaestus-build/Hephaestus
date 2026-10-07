---
"hephaestus": patch
---

Pull-based deployments let the running worker drain within the grace period it was started with before replacing its dependencies, also when the new release removes the worker. A failed stop applies nothing and records no release. This applies once a host adopts the updated reconciler.
