---
"hephaestus": patch
---

The pull-based reconciler now treats the step from a release candidate to its stable release, such as `1.0.0-rc.6` to `1.0.0`, as a stopped cutover. Before, a host that ran a candidate applied 1.0.0 as a rolling upgrade, though [Upgrade to 1.0](/admin/upgrade-to-1-0) requires every role to stop.
