#### 🔴 PostgreSQL 18 and baseline synchronization required

PostgreSQL 18 is the only supported database major version. The bundled image no longer accepts a PostgreSQL 17 build target.

If your database is still on PostgreSQL 17, first complete the [v0.77.4 PostgreSQL 17-to-18 upgrade procedure](https://github.com/hephaestus-build/Hephaestus/blob/v0.77.4/docs/admin/backup-restore.mdx#postgresql-17-to-18). Verify a successful restore into PostgreSQL 18 and keep an off-host backup before removing the old database. Then install this release. Do not attach a PostgreSQL 17 data directory to the PostgreSQL 18 image.

All existing databases, including PostgreSQL 18 installations, must complete the [baseline synchronization runbook](https://docs.hephaestus.build/admin/liquibase-baseline-runbook) before the candidate application starts. Take and test-restore a full backup, verify the v0.77.4 cut-point, stop writers, and run `changeLogSyncToTag baseline_v0_77_4` using the candidate image. Unsynchronized existing schemas fail startup. Fresh databases apply the baseline automatically.
