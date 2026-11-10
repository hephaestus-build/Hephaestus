#### 🔴 Give each database purpose its own login

Before 1.0, the server, the worker, the migration Job and the bundled PostgreSQL shared one login: the PostgreSQL superuser in `DATABASE_USERNAME` and `DATABASE_PASSWORD`.
Now each purpose has its own role ([Database roles](https://docs.hephaestus.build/admin/kubernetes#database-roles)):

- The server logs in as `hephaestus_app`, with `DATABASE_APP_PASSWORD`. It reads and writes rows only.
- The migration Job logs in as `hephaestus_owner`, with `DATABASE_OWNER_PASSWORD`. It owns the database and its schema objects.
- The administrator of the bundled PostgreSQL moves to `DATABASE_ADMIN_USERNAME` and `DATABASE_ADMIN_PASSWORD`. No Hephaestus process logs in with it.

The chart's `files/database-roles.sql` creates both roles and moves the existing schema objects to `hephaestus_owner`.
`REASSIGN OWNED BY` cannot do this for the bundled PostgreSQL: it refuses the bootstrap superuser, which owns the system catalogs.
`helm upgrade` stops while a key is missing, or while the Secret still holds `DATABASE_USERNAME` or `DATABASE_PASSWORD`.

**Operators:** do these steps with the [Database maintenance](https://docs.hephaestus.build/admin/kubernetes#database-maintenance) phases.
The steps use the namespace `hephaestus` and the Secret `hephaestus-app`.

1. Back up the database.
2. Do phase 1 with the chart and values of the current release: stop the server and the worker.
   The migration then moves the schema objects while nothing else holds a lock on them.
3. Change the Secret.
   - With the bundled PostgreSQL, move the administrator login to its new keys:

     ```bash
     kubectl -n hephaestus get secret hephaestus-app -o json \
       | jq '.data |= (. + {DATABASE_ADMIN_USERNAME: .DATABASE_USERNAME, DATABASE_ADMIN_PASSWORD: .DATABASE_PASSWORD} | del(.DATABASE_USERNAME, .DATABASE_PASSWORD))' \
       | kubectl replace -f -
     ```

     Then run `hephaestus-keys.sh` of the new release, as [Create the Secret](https://docs.hephaestus.build/admin/kubernetes#create-the-secret) describes.
     It adds `DATABASE_OWNER_PASSWORD` and `DATABASE_APP_PASSWORD`.
     The migration Job of the next revision creates both roles with these passwords.
   - With an external PostgreSQL, run `files/database-roles.sh` of the new release as your administrator, as [Database roles on an external PostgreSQL](https://docs.hephaestus.build/admin/kubernetes#database-roles-on-an-external-postgresql) describes.
     Then put `DATABASE_OWNER_PASSWORD` and `DATABASE_APP_PASSWORD` in the Secret, and remove `DATABASE_USERNAME` and `DATABASE_PASSWORD`.
4. Do phases 3 and 4 with the new release.
   The migration Job sets up the roles, moves the schema objects and migrates as `hephaestus_owner`.
   With the bundled PostgreSQL, PostgreSQL restarts once, because it reads its administrator from the new keys.
5. Check that the server connects as `hephaestus_app`:

   ```bash
   kubectl -n hephaestus exec hephaestus-postgresql-0 -c postgresql -- \
     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT DISTINCT usename FROM pg_stat_activity WHERE application_name = '"'"'PostgreSQL JDBC Driver'"'"'"'
   ```

   It prints `hephaestus_app` only.
6. Update your copy of the keys outside the cluster: `DATABASE_ADMIN_USERNAME` replaces `DATABASE_USERNAME` ([Copy the keys from the Secret](https://docs.hephaestus.build/admin/secret-custody#copy-the-keys-from-the-secret)).
7. With an external PostgreSQL, remove the old login when nothing else uses it.

If you start the application image outside the chart, give the migrate run mode the login of `hephaestus_owner`, and the server the login of `hephaestus_app`.
Before each migration that changes a password, run `files/database-roles.sh` as the administrator.

To go back to the previous release, put `DATABASE_USERNAME` and `DATABASE_PASSWORD` back with the administrator's values.
The previous release logs in as the administrator again, so the moved objects need no change.
