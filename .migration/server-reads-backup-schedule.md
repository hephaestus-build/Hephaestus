#### 🔴 State the Kubernetes API endpoint for backups without sandboxes

- **When:** before the window
- **Action:** With `postgresql.backup.enabled` and without `sandbox.enabled`, set `networkPolicy.kubernetesApi.cidr` and `networkPolicy.kubernetesApi.port`.

The server now reads the two backup CronJobs for `HephaestusBackupStale` ([Backup metrics](https://docs.hephaestus.build/admin/metrics#backup-metrics)). It reaches the Kubernetes API only at `networkPolicy.kubernetesApi`, so the chart refuses to render with backups on and no endpoint. Installations with sandboxes already state it.

Set the address and port that the network plugin sees after address translation, as [Write the values file](https://docs.hephaestus.build/admin/kubernetes#write-the-values-file) describes.

If your own Prometheus loads the rule group `hephaestus-kubernetes` and its kube-state-metrics exports CronJob labels only for this rule, you can drop `cronjobs=[…]` from `--metric-labels-allowlist`.
