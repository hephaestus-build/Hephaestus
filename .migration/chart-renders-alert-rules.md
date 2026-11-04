#### 🔴 The alert rule file has a new path

**Affected**: deployments that load the alert rules from the repository or from a release's source tree.

The rule file and its promtool tests moved from `deploy/observability/` to `deploy/chart/hephaestus/files/`, so that the chart can render them. A rule loader that still reads the old path finds no file.

**Migration**: load `deploy/chart/hephaestus/files/hephaestus-alerts.yml` from the release that you run. Or let the chart give your cluster the rules with each upgrade:

- Set `observability.alertRules.configMap`, mount the ConfigMap into your Prometheus or vmalert, and point its rule loader at the mounted file.
- Set `observability.alertRules.prometheusRule`, and make the `ruleSelector` and `ruleNamespaceSelector` of your Prometheus match it. `observability.alertRules.labels` adds the labels that the selector reads.

After the upgrade, check that your Prometheus or vmalert lists the Hephaestus rules.
