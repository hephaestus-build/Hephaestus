#### 🔴 Update the restore lockdown path

The lockdown SQL now ships inside the signed Helm chart.
In a source checkout, its path is `deploy/chart/hephaestus/files/restore-clone-lockdown.sql`.
In a verified release chart, use `chart-$VERSION/hephaestus/files/restore-clone-lockdown.sql`.
Update any restore script that reads the earlier `docker/self-host/restore-clone-lockdown.sql` path.

On Kubernetes, stop all application replicas and set `migration.enabled=false` before starting a restored database.
Apply the SQL with `ON_ERROR_STOP=1`, then verify silent mode and paused feedback.
Keep provider and model egress denied independently of silent mode.
Enable migration and start the server alone only after those checks pass.
The [restore procedure](https://docs.hephaestus.build/admin/kubernetes#restore) gives each phase.
