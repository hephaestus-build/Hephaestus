---
"hephaestus": minor
---

`HephaestusBackupStale` now fires on every installation with backups, also with only the bundled monitoring stack, and also when a backup never runs. The server reads when a backup last succeeded from the two backup CronJobs and reports the age as `database_backup_age_seconds`. A backup Job that waits `Pending` on a full node fails no Job, but its age grows, so the alert fires after 30 hours. When the server cannot read the CronJobs, or one is missing, the alert fires after 15 minutes. The rule no longer needs kube-state-metrics.

The alert moves to its own rule group, `hephaestus-backups`. The chart renders that group only with backups on, in the bundled stack and in the alert rule ConfigMap and PrometheusRule, because only then does the server report a backup age. The ConfigMap now holds the groups that apply to the release, not the rule file unchanged.

With backups on, the server gets one new permission: `get` on exactly those two CronJobs, in the release namespace. Without backups or sandboxes, the server still has no Kubernetes API token.

**Operators:** With `postgresql.backup.enabled` and without `sandbox.enabled`, state `networkPolicy.kubernetesApi`. The chart refuses to render without it.
