# Synchronizing the v0.77.4 database baseline

PostgreSQL 18 is required. Fresh installations apply the baseline automatically. Existing databases
must finish the v0.77.4 migrations and synchronize the baseline before starting the candidate.
Unsynchronized existing schemas fail startup.

## Fresh self-hosted installations

Use the same baseline files as every other installation; do not edit them for your hostname, database
name or database user. Those values belong in deployment configuration. The `v0.77.4` suffix identifies
the schema cut-point, not a particular operator or deployment.

The [supported self-hosted stack](install) supplies PostgreSQL 18 with `citext` and `btree_gist` in
`public` and `pg_partman` in `partman`. The baseline uses these schemas explicitly; it is not a
schema-per-tenant or arbitrary-extension-schema installer. Database provisioning must permit the
migration connection to create the required extensions and application objects.

Production deployments must use the `prod` Spring profile, as the shipped Compose stack does. It
selects the audit protections that development deliberately omits. A fresh database initializes
without baseline synchronization; no accounts, workspaces or production credentials are imported.
The [first-login consent limitation](legal-pages#first-login-consent-notice) still applies to non-TUM
operators; schema portability does not resolve operator-specific legal configuration.

## Before deployment

1. Verify the currently running release has completed every v0.77.4 migration with no pending
   changesets. Match migration identities by **id, author and logical filename**, not a row count.
   Resolve schema drift before synchronization.
2. Rehearse with an isolated restoration of the environment's full backup. Verify application rows,
   operator settings, consent data, pg_partman registration and partition maintenance survive.
3. Stop every application process that can write to this database, including server, worker and
   webhook runtimes. Prevent automatic restarts during the transition.
4. Take a full custom-format `pg_dump`, retain the matching release lock and configuration, and copy
   them off-host. Require a successful restore rehearsal, not just an archive listing. Follow
   [Backup & Restore](backup-restore.mdx). Never use a schema-only dump as a rollback backup.
5. Record the release engineer, source release, backup checksum, restore result and target image
   digest in the deployment record. Keep credentials in the existing secret-management mechanism.

Do not synchronize an empty or partially upgraded database. `changeLogSyncToTag` records changesets;
it does not validate their schema or execute initialization.

## Synchronize without executing DDL

Use the **candidate** application image's bundled Liquibase and changelog:

Set `CANDIDATE_IMAGE` to the verified image digest, `DATABASE_NETWORK` to the database's Docker
network. Set `LIQUIBASE_DEFAULTS_FILE` to a protected Liquibase properties file containing that
environment's `url`, `username`, and `password`. Make it readable only by the image's non-root runtime
user and the operator, then mount it read-only. Do not put credentials in command arguments, shell
history, or the repository.

```bash
docker run --rm --network "$DATABASE_NETWORK" \
  --mount "type=bind,src=$LIQUIBASE_DEFAULTS_FILE,dst=/run/secrets/liquibase.properties,readonly" \
  --entrypoint /cnb/lifecycle/launcher "$CANDIDATE_IMAGE" -- \
  java -cp 'runner.jar:lib/*' liquibase.integration.commandline.Main \
  --defaultsFile=/run/secrets/liquibase.properties --changeLogFile=db/master.xml \
  --contexts=prod changeLogSyncToTag baseline_v0_77_4
```

This marks schema, production audit triggers, initialization and the tag as already applied. Existing
operator settings and business rows are not reset. Use `--contexts=dev` only for development databases;
production audit restrictions intentionally differ from development fixtures.

Verify the four baseline entries before starting the candidate:

```sql
SELECT id, author, filename, md5sum, exectype, tag
FROM databasechangelog
WHERE filename = 'db/changelog/0000000000000_baseline_v0_77_4.xml'
ORDER BY orderexecuted;
```

They must have author `hephaestus-release`, non-null checksums, and ids `baseline_v0_77_4`,
`baseline_v0_77_4-audit`, `baseline_v0_77_4-seed`, and `baseline_v0_77_4-tag`. The last row carries tag
`baseline_v0_77_4`. A `dev` synchronization has three entries because it excludes audit triggers.

Start the candidate and verify readiness, authenticated workspace reads, consent, operator settings,
and partition maintenance. The baseline must execute no DDL on this synchronized database. Later
migrations, if present in the candidate, still run normally; do not synchronize beyond the baseline tag.

## Stale lock recovery

First confirm no migration process is still running. Never clear a live process's lock. Use the same
candidate-image command and connection parameters, replacing `changeLogSyncToTag baseline_v0_77_4`
with `releaseLocks`, then verify:

```sql
SELECT id, locked, lockgranted, lockedby FROM databasechangeloglock;
```

`locked` must be false before retrying. Do not delete the lock table or disable locking.

## Rollback

The baseline has no rollback block. **Do not use `liquibase rollback`** to undo synchronization or a
squashed schema. Stop all writers and restore the full pre-deployment backup into a clean database.
For a PostgreSQL connection configured through `PGHOST`, `PGPORT`, `PGUSER` and a protected password
file, the database portion is:

```bash
(
set -eu
: "${BACKUP_FILE:?Set BACKUP_FILE to the verified full backup}"
pg_restore --list "$BACKUP_FILE" >/dev/null
psql --dbname=postgres --set=ON_ERROR_STOP=1 \
  --command="DROP DATABASE hephaestus WITH (FORCE)"
psql --dbname=postgres --set=ON_ERROR_STOP=1 --command="CREATE DATABASE hephaestus"
pg_restore --dbname=hephaestus --no-owner --no-acl --single-transaction "$BACKUP_FILE"
psql --dbname=hephaestus --set=ON_ERROR_STOP=1 \
  --command="SELECT count(*) FROM databasechangelog WHERE id = 'baseline_v0_77_4-tag' AND author = 'hephaestus-release'"
)
```

These commands assume the bundled single-role deployment. If you manage additional database roles,
restore their ownership and grants separately before starting writers.

Expect zero baseline-tag rows in a pre-squash backup. Redeploy the previous **signed image and release
lock** only after the [recovery checks](backup-restore.mdx#before-restarting-services), then verify
readiness and restored data. A Git revert alone neither restores the database nor selects the
previous image.

> **Never run `clearCheckSums` in production or staging.** It does not repair schema drift and removes
> checksum evidence. Reserve it for disposable developer databases after understanding the mismatch;
> prefer `vp run dev:reset` when local data can be discarded.

## Release gates

- Record a seven-calendar-day staging soak against a restored production snapshot before production.
- Freeze new changelogs for at least 48 hours after production deployment.
- Keep `docs/db/archive/v0.77.4/` for at least two release cycles. Historical regression tests use it
  only as test resources; it must not enter the application JAR.
- Announce the cut-point and synchronization requirement before merging. Developer reset:
  `vp run dev:reset`, then `vp run dev`. This destroys local database data.
