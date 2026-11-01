#### 🔴 Stop every role for the upgrade to 1.0

Upgrade to 0.95.x on the Helm chart first. Follow [Upgrade from 0.x to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0). Back up PostgreSQL and JetStream, verify the restore, and export each survey's results before migration.

Apply ADR 0070’s cutover order: stop the server and workers and wait until their sandbox Pods and Jobs are gone. Keep the database-free 0.95.x webhook receiver accepting into JetStream during this drain. The 1.0 chart then replaces that receiver and runs the migration with workers stopped. Start the new server alone, then restore the recorded replica counts from the selected chart. The webhook receiver exception applies only to this cutover’s drain; no old database writer runs during or after migration. Do not run old application writers on the new schema or use an automatic Helm rollback after migration.

Claimed reviews resume without spending a retry and keep their admitted and staged results. A conversation turn in progress ends with “Ask again” and is not retried silently. Recovery after a failed migration requires the verified paired backup and the previous release. Preserve encryption keys and their versions, and apply the individual setting and data steps in this release guide before starting the new roles.
