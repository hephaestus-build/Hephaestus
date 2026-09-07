#### 🔴 PostgreSQL 18 required

PostgreSQL 18 is the only supported database major version. The bundled image no longer accepts a PostgreSQL 17 build target.

If your database is still on PostgreSQL 17, first complete the [v0.77.4 PostgreSQL 17-to-18 upgrade procedure](https://github.com/hephaestus-build/Hephaestus/blob/v0.77.4/docs/admin/backup-restore.mdx#postgresql-17-to-18). Verify a successful restore into PostgreSQL 18 and keep an off-host backup before removing the old database. Then install this release. Do not attach a PostgreSQL 17 data directory to the PostgreSQL 18 image.

No database upgrade is needed for installations already running PostgreSQL 18.
