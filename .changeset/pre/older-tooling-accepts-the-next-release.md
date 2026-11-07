---
"hephaestus": minor
---

A host's reconciler now accepts a later release that adds or removes a chart image, and each release proves that the previous release's verifier accepts it before it publishes.

**Operators:** a pull-based host with reconciler tooling from before 1.0.0-rc.4 stops at that release with `does not pin the v1.0.0-rc.4 images`. Follow [Hosts with reconciler tooling from before 1.0.0-rc.4](https://docs.hephaestus.build/admin/upgrade#hosts-with-reconciler-tooling-from-before-100-rc4) once, before you resume the timer.
