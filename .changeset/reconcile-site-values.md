---
"hephaestus": patch
---

The pull-based reconciler applies a change to `/etc/hephaestus/chart.yaml` or to `HEPHAESTUS_CHART_PROFILE` at its next tick. Before, such a change waited for the next promotion, and so did undoing it. The reconciler keeps a digest of these values in `applied.json`. A host that updates its tooling adopts the values that run, without a Helm upgrade.
