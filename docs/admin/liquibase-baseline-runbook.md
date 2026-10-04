# Synchronizing the v0.77.4 database baseline

PostgreSQL 18 is required.
Fresh installations apply the baseline automatically.
Before the target release starts, existing databases must finish the v0.77.4 migrations and synchronize the baseline.
Unsynchronized existing schemas fail startup.

## Fresh self-hosted installations

All installations use the same baseline files.
Do not edit them for your hostname, database name, or database user.
Those values belong in deployment configuration.
The `v0.77.4` suffix identifies the schema cut-point, not a particular operator or deployment.

The [supported self-hosted stack](install) supplies PostgreSQL 18 with `citext` and `btree_gist` in `public`.
It supplies `pg_partman` in `partman`.
The baseline explicitly uses these schemas.
It does not install schemas per tenant or extensions in arbitrary schemas.
Database provisioning must permit the migration connection to create the required extensions and application objects.

Production deployments must use the `prod` Spring profile, as the shipped Compose stack does.
It selects the audit protections that development deliberately omits.
A fresh database initializes without baseline synchronization.
Initialization imports no accounts, workspaces, or production credentials.

## Before deployment

**CAUTION:** Do not synchronize an empty or partially upgraded database.
`changeLogSyncToTag` records changesets.
It does not validate their schema or execute initialization.
Never use a schema-only dump as a rollback backup.

The target release's changelog rejects older migration history for both startup and synchronization.

1. Read the [upgrade-path table](compatibility-policy#upgrade-paths-to-10).
2. If the database is older than v0.77.4, install v0.77.4.
3. In that case, start v0.77.4 once before you continue.
4. Verify that the current release completed every v0.77.4 migration with no pending changesets.
5. Match migration identities by **id, author and logical filename**, not a row count.
6. Resolve schema drift before synchronization.
7. Rehearse with an isolated restoration of the environment's full backup.
8. Verify that application rows, operator settings, consent data, pg_partman registration, and partition maintenance survive.
9. Stop every application process that can write to this database, including server, worker, and webhook runtimes.
10. Prevent automatic restarts during the transition.
11. Take a full custom-format `pg_dump`.
12. Retain the matching release lock and configuration.
13. Copy the backup, release lock, and configuration off-host.
14. Require a successful restore rehearsal, not just an archive list.
15. Follow [Backup & Restore](backup-restore.mdx).
16. Record the release engineer, source release, backup checksum, restore result, and target image digest in the deployment record.
17. Keep credentials in the existing secret-management mechanism.

## Synchronize without executing DDL

The procedure uses the **target release** application image's bundled Liquibase and changelog.
For the 1.0 upgrade, this is the signed **v1.0.0** image, not the v0.77.4 image.
Do not put credentials in command arguments, shell history, or the repository.

1. Set `TARGET_IMAGE` to the verified image digest.
2. Set `DATABASE_NETWORK` to the database's Docker network.
3. Set `LIQUIBASE_DEFAULTS_FILE` to a protected Liquibase properties file.
   The file contains that environment's `url`, `username`, and `password`.
4. Make the file readable only by the image's non-root runtime user and the operator.
5. Mount the file read-only.
6. Read the runtime user from the image instead of an assumed value.
7. Give that user ownership of the file:

   ```bash
   docker inspect --format '{{.Config.User}}' "$TARGET_IMAGE"   # e.g. 1002:1001
   chown 1002:1001 "$LIQUIBASE_DEFAULTS_FILE" && chmod 0600 "$LIQUIBASE_DEFAULTS_FILE"
   ```

   A file owned by `root` with mode `0600` can seem protected but fail this check.
   The container cannot read it.
   Liquibase aborts with `java.nio.file.AccessDeniedException: /run/secrets/liquibase.properties`.
   This error names the mount, not the permission.

8. First, verify access with the read-only `status` command.
   Use the same image, mount, and network, with no writes.
9. Only then, run synchronization:

   ```bash
   docker run --rm --network "$DATABASE_NETWORK" \
     --mount "type=bind,src=$LIQUIBASE_DEFAULTS_FILE,dst=/run/secrets/liquibase.properties,readonly" \
     --entrypoint /cnb/lifecycle/launcher "$TARGET_IMAGE" -- \
     java -cp 'runner.jar:lib/*' liquibase.integration.commandline.Main \
     --defaultsFile=/run/secrets/liquibase.properties --changeLogFile=db/master.xml \
     --contexts=prod changeLogSyncToTag baseline_v0_77_4
   ```

   This marks the schema, production audit triggers, initialization, and tag as already applied.
   It does not reset existing operator settings or business rows.
   Use `--contexts=dev` only for development databases.
   Production audit restrictions intentionally differ from development fixtures.

10. Before the target release starts, verify the four baseline entries:

    ```sql
    SELECT id, author, filename, md5sum, exectype, tag
    FROM databasechangelog
    WHERE filename = 'db/changelog/0000000000000_baseline_v0_77_4.xml'
    ORDER BY orderexecuted;
    ```

    They must have author `hephaestus-release` and non-null checksums.
    Their ids must be `baseline_v0_77_4`, `baseline_v0_77_4-audit`, `baseline_v0_77_4-seed`, and `baseline_v0_77_4-tag`.
    The last row carries tag `baseline_v0_77_4`.
    A `dev` synchronization has three entries because it excludes audit triggers.

11. Use the [guarded startup sequence](production-operations-runbook#silent-deployment-checklist).
12. Verify readiness, authenticated workspace reads, consent, operator settings, and partition maintenance.

The baseline must execute no DDL on this synchronized database.
Later migrations, if present in the target release, still run normally.
Do not synchronize beyond the baseline tag.

## Stale lock recovery

**CAUTION:** Never clear a live process's lock.
Do not delete the lock table or disable locking.

1. Confirm that no migration process is still active.
2. Use the same target-release image command and connection parameters.
   Replace `changeLogSyncToTag baseline_v0_77_4` with `releaseLocks`.
3. Verify the lock:

```sql
SELECT id, locked, lockgranted, lockedby FROM databasechangeloglock;
```

`locked` must be false before you retry.

## Rollback

The baseline has no rollback block.
**CAUTION:** Do not use `liquibase rollback` to undo synchronization or a squashed schema.
Restore the full pre-deployment backup into a clean database.

The database commands below use a PostgreSQL connection configured through `PGHOST`, `PGPORT`, `PGUSER`, and a protected password file.
They assume the bundled single-role deployment.

1. Stop all writers.
2. Restore the full pre-deployment backup into a clean database:

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

3. If you manage additional database roles, restore their ownership and grants separately before writers start.
4. Verify that a pre-squash backup has zero baseline-tag rows.
5. Complete the [recovery checks](backup-restore.mdx#before-restarting-services).
6. Only then, redeploy the previous **signed image and release lock**.
7. Verify readiness and restored data.

A Git revert alone neither restores the database nor selects the previous image.

**CAUTION:** Never run `clearCheckSums` in production or staging.
It does not repair schema drift and removes checksum evidence.
Reserve it for disposable developer databases after you understand the mismatch.

1. If you can discard local data, prefer this command:

   ```bash
   vp run dev:reset
   ```

Contributor [release gates](../contributor/database-migration#baseline-release-gates) define the release checks.
Operators record the deployment actions in their deployment record.
