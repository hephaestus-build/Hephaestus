#### 🔴 Stop every role before the 0.95.0 database migration

Under the `prod` profile, runtime roles do not migrate the database at startup.
The server and worker wait for every database change set of their release.
The wait ends after `hephaestus.database.migration-wait` (`HEPHAESTUS_DATABASE_MIGRATION_WAIT`, default `10m`).
A role then stops and names the first change set that has not run.

Stop every previous-version runtime role before the 0.95.0 migration.
Keep a verified database backup and the matching release lock.
The bundled Compose stacks contain a one-shot `database-migration` service.
Run `docker compose run --rm -T --no-deps database-migration` while the old roles are stopped.
Start the new roles only after it exits successfully.

If you start the application image outside the bundled Compose stacks, use these steps:

1. Run the application image once with `SPRING_PROFILES_ACTIVE=prod,migrate`.
   Give it `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD`, as the runtime roles receive them.
   It exits with status 0 when the migration completes.
2. Start the server, worker and webhook roles of that release after it completes.
3. If a role has `SPRING_LIQUIBASE_ENABLED=true`, remove that override.

An image rollback cannot reverse the schema upgrade.
Use the [failed-migration recovery procedure](https://docs.hephaestus.build/admin/upgrade#if-migration-fails).
See [Database migrations](https://docs.hephaestus.build/admin/runtime-roles#database-migrations) for the role contract.
