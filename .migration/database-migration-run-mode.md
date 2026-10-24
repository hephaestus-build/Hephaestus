#### 🔴 Run the database migration before the runtime roles start

Under the `prod` profile, no runtime role migrates the database at start.
The server, the worker and the webhook receiver wait until the database holds every change set of their release.
After 10 minutes (`hephaestus.database.migration-wait`), a role stops and names the first change set that has not run.
The bundled Compose stacks already contain a one-shot `database-migration` service that runs before the roles.

**Operators:** do these steps only if you start the application image outside the bundled Compose stacks.

1. For each release, run the application image once with `SPRING_PROFILES_ACTIVE=prod,migrate`.
   Give it `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD`, as the roles get them.
   It exits with status 0 when the migration is complete.
2. Start the server, worker and webhook roles of that release after it completes.
3. If you set `SPRING_LIQUIBASE_ENABLED=true` on a role, remove it.

See [Database migrations](https://docs.hephaestus.build/admin/runtime-roles#database-migrations).
