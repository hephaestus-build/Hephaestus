#### 🔴 Update the installed reconciler before the 1.0 chart upgrade

Before promoting 1.0, install the verified 0.95.1 reconciler and its units from `deploy/reconcile/`.
Follow [Pull-based deployment](https://docs.hephaestus.build/admin/pull-based-deployment#follow-a-channel-with-the-chart) for the one-time tooling update.
The 0.95.0 reconciler cannot apply a release channel with the chart.

For 0.95.x → 1.0, stop the writers with the verified source chart first.
The reconciler starts the target server with workers stopped.
It does not roll back a failed migration.
Follow the stopped upgrade and database recovery procedures before restoring the worker count.
