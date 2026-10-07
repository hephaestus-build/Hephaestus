---
"hephaestus": patch
---

Pull-based deployments let the worker drain within its configured grace period before replacing its dependencies. A failed stop applies nothing and records no release. This applies once a host adopts the updated reconciler.
