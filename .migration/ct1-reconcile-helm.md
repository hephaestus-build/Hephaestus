#### 🔴 Move release reconciliation to Helm

Before upgrading to 1.0, move Compose installations to the Helm chart on k3s or Kubernetes.
Follow the [versioned migration guide](https://github.com/hephaestus-build/Hephaestus/blob/v0.95.0/docs/admin/compose-to-kubernetes.mdx).
Keep the application version at 0.95.0 during that move.

Install the 0.95.1 reconciler before promoting 1.0, as [pull-based deployment](https://docs.hephaestus.build/admin/pull-based-deployment#follow-a-channel-with-the-chart) describes.
Pause reconciliation and wait for its active run to finish before replacing the units and tooling link.
Put site values in `/etc/hephaestus/chart.yaml` and credentials in cluster Secrets.
Keep the existing database credentials, encryption keys, auth keys and provider credentials.
Remove `HEPHAESTUS_STACKS` and `HEPHAESTUS_MIN_FREE_GIB` from `/etc/hephaestus/reconcile.env`.
Set `HEPHAESTUS_WAIT_TIMEOUT=1200`, or keep a larger timeout that your deployment needs.
The reconciler now uses Helm for every deployment.

For the major upgrade, follow [Database maintenance](https://docs.hephaestus.build/admin/kubernetes#database-maintenance) with all writers stopped.
If the cutover stops, keep reconciliation paused and follow [Recover a stopped cutover](https://docs.hephaestus.build/admin/pull-based-deployment#recover-a-stopped-cutover).
A rollback across the 1.0 schema boundary requires a database restore, even with `allowRollback`.
