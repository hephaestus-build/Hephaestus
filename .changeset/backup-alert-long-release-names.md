---
"hephaestus": patch
---

`HephaestusBackupStale` now also finds the backup CronJobs of a release whose name is longer than 34 characters, whose CronJob names the chart shortens. It finds them by their labels, so add `cronjobs=[app.kubernetes.io/name,app.kubernetes.io/component,app.kubernetes.io/instance]` to the label allowlist of kube-state-metrics. Releases with shorter names need no change.
