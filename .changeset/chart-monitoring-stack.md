---
"hephaestus": minor
---

The chart can now run a small monitoring stack beside Hephaestus. Set `observability.stack.enabled` and name a Secret with your `alertmanager.yml` in `observability.stack.alertmanager.configSecret`. VictoriaMetrics then scrapes each role and keeps 30 days of metrics, vmalert evaluates the release's alert rules, and Alertmanager sends the alerts to your receivers. The stack needs `networkPolicy.kubernetesApi`, and it uses no host path and no cluster-wide permission. The new `HephaestusWatchdog` alert fires for as long as the monitoring works: route it to an outside dead-man check. The release now pins and verifies the VictoriaMetrics, vmalert and Alertmanager images.
