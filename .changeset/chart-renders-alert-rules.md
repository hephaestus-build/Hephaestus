---
"hephaestus": minor
---

The chart can now give your cluster the alert rules of the release that it installs, so the rules change with each upgrade. Set `observability.alertRules.configMap` for a ConfigMap that a Prometheus or a vmalert mounts, or `observability.alertRules.prometheusRule` for a PrometheusRule of the Prometheus Operator. Both are off by default. 
**Operators:** the rule file moved from `deploy/observability/hephaestus-alerts.yml` to `deploy/chart/hephaestus/files/hephaestus-alerts.yml`. If you load the file from the repository, change its path when you upgrade.
