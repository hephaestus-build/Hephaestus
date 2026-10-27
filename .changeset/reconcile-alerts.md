---
"hephaestus": patch
---

The shipped Prometheus alert rules now cover pull-based deployment. A new group, `hephaestus-pull-deploy`, warns when every reconcile run of a host fails for 15 minutes, for example because the release's chart refuses the host's values, when a host finishes no run for 40 minutes, when no host reports the reconciler's metrics, and when a host runs tooling that no apply recorded. Load the group only where Prometheus scrapes a host that runs the reconciler.
