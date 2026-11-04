#### 🔴 The alert rule file has a new path

**Affected**: deployments that load the alert rules from the repository or from a release's source tree.

The rule file and its promtool tests moved from `deploy/observability/` to `deploy/chart/hephaestus/files/`, so that the chart can render them. A rule loader that still reads the old path finds no file.

**Migration**: load `deploy/chart/hephaestus/files/hephaestus-alerts.yml` from the release that you run. Or set `observability.alertRules.configMap` or `observability.alertRules.prometheusRule`, and the chart gives your cluster the rules with each upgrade.
