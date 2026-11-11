#### 🔴 Remove existing sandbox claims before starting the new workers

Stop the workers, then remove their remaining sandbox workloads with foreground deletion before you start the new workers.
Follow steps 3 and 4 of the [Planned node maintenance procedure](https://docs.hephaestus.build/admin/maintenance#planned-node-maintenance).
Use the release's own sandbox namespace.
Existing claims keep their old expiry policy.
Every claim created by the new workers uses `DeleteForeground`.
Do not proceed while sandbox Pods remain.
