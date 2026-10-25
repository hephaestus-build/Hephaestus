---
"hephaestus": minor
---

A pull-based host can now follow its channel with the Helm chart. Set `HEPHAESTUS_STACKS=chart` and put your chart values in `/etc/hephaestus/chart.yaml`. Each promotion still verifies the signed channel and its lock first, and then runs one `helm upgrade --install` with the images that the lock names, rolling back when the migration or a Pod fails. The optional settings `HEPHAESTUS_CHART_RELEASE`, `HEPHAESTUS_CHART_NAMESPACE`, `HEPHAESTUS_CHART_PROFILE` and `HEPHAESTUS_HELM` name the release, its namespace, the chart profiles in the order they apply (for example `"small small-16gib"`) and the Helm binary. The reconcile unit no longer requires Docker, so a host without it can run the reconciler.
