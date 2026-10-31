#### 🔴 Stop every role for the upgrade to 1.0

Upgrade to 0.95.x on the Helm chart first. Follow [Upgrade from 0.x to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0) and its stopped cutover procedure. Back up PostgreSQL and JetStream, verify the restore, and export each survey's results before migration.

Stop the server, workers and all their sandbox Pods and Jobs before the chart migration. The old webhook receiver can buffer into JetStream during the drain; the cutover chart replaces it. Start the new server alone, then restore the recorded worker and conversation-loop replicas. Do not run old application writers on the new schema or use an automatic Helm rollback after migration.

Claimed reviews resume without spending a retry and keep their admitted and staged results. A conversation turn in progress ends with “Ask again” and is not retried silently. Recovery after a failed migration requires the verified paired backup and the previous release. Preserve encryption keys and their versions, and apply the individual setting and data steps below before starting the new roles.
