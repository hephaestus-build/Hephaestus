#### 🔴 Stop every role before you upgrade from 1.0.0-rc.1

1.0.0-rc.1 cannot run on the schema of this release.
The migration drops the column `llm_call_pool.interactive_reserve` and replaces the unique constraint `uq_issue_provider_native_id`, and 1.0.0-rc.1 still uses both.

Follow [Stopped schema upgrades](https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades):

1. Back up PostgreSQL and JetStream, and verify the restore.
2. Stop every role with the chart of 1.0.0-rc.1: the server, the workers and the webhook.
   Wait until their Pods and the sandbox Pods and Jobs are gone.
3. Run the migration of this release with every role stopped.
4. Start every role from this release.

Do not use `--rollback-on-failure`.
With [pull-based deployment](https://docs.hephaestus.build/admin/pull-based-deployment#operating-it), pause the reconcile timer before step 2.
For an upgrade within one major version, the reconciler does not stop the roles.
Promote this release and resume the timer only after step 4.

To roll back, stop every role and restore the backup from step 1. Then start 1.0.0-rc.1.
There is no in-place downgrade.
If the migration fails, follow [If migration fails](https://docs.hephaestus.build/admin/upgrade#if-migration-fails).
