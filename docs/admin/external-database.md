---
title: Use an external PostgreSQL
description: Set up the database roles on your own PostgreSQL, and connect the release to it.
page_type: how-to
---

By default, the chart runs its own PostgreSQL.
Use this procedure to run the release against a PostgreSQL server that you operate.
[Database roles](./helm-chart#database-roles) describes each role that the release uses.

## Before you start

- PostgreSQL 18, the version of the bundled PostgreSQL.
- The extensions `pg_partman`, `citext` and `btree_gist` on the server. The migrations create them in the database.
  `citext` and `btree_gist` come with PostgreSQL. `pg_partman` is a separate package. On a managed service, check its list of extensions.
- `wal_compression = lz4`, or `zstd`, on the server, as the bundled PostgreSQL sets it.
  Without it, a streamed answer writes about 1.5 times more write-ahead log. Most of that log is full-page images of index pages.
- `psql` 15 or later, and Perl with the module `Digest::SHA`, on the computer that runs the script below ([Database roles](./helm-chart#database-roles)).
- An empty database for Hephaestus, `hephaestus` by default (`postgresql.database`). Create it as the administrator of your PostgreSQL:

  ```sql
  CREATE DATABASE hephaestus;
  ```

## Set up the database roles

The chart never gets the administrator login of your PostgreSQL, so you set up the roles yourself.
Do this before the first install, after each change of their passwords, and after each update of the server's extensions.

1. Get the folder `files/` from the verified chart of the release, as [Verify the release chart](./kubernetes#verify-the-release-chart) describes.
2. Generate a password for each role:

   ```bash
   export DATABASE_OWNER_PASSWORD="$(openssl rand -hex 24)"
   export DATABASE_APP_PASSWORD="$(openssl rand -hex 24)"
   ```

3. Run the script as an administrator of your PostgreSQL, with the Hephaestus database.
   It reads the two exported passwords and sends PostgreSQL only their verifiers:

   ```bash
   sh "chart-$VERSION/hephaestus/files/database-roles.sh" \
     "host=<host> dbname=hephaestus user=<administrator> sslmode=verify-full"
   ```

4. Put both passwords in the Secret, as [Create the Secret](./kubernetes#create-the-secret) describes with `--external-database`.

The administrator can be a superuser or a role with `CREATEROLE` and `CREATEDB`, as a managed PostgreSQL service gives:

- It must own the Hephaestus database, or be a member of its owner.
- If the roles exist, it must have created them, or have the `ADMIN` option on them.
- The file makes it a member of `hephaestus_owner`, with `INHERIT` and `SET`.
  It can then give the database and the objects to that role.
  The administrator can also act as `hephaestus_owner`, and the row-level security of its tables does not apply to it.

Only a superuser may change the settings that `database-quiet.sql` turns off.
For each setting that the administrator may not change, the file checks the value that applies:

- If `pg_stat_statements.track_utility` is `on`, or `log_statement` is not `none`, the file stops.
- If `log_min_duration_statement` or `log_min_duration_sample` is not `-1`, the file stops.
- If `log_transaction_sample_rate` is not `0`, the file stops.
- If `log_min_error_statement` is not `panic`, the file continues with a warning.
  Then a statement of the run that fails writes its verifier to the server log.

Before you run the files, set these values for the administrator at the service:

- `pg_stat_statements.track_utility = off`
- `log_statement = none`
- `log_min_duration_statement = -1` and `log_min_duration_sample = -1`
- `log_transaction_sample_rate = 0`
- `log_min_error_statement = panic`

Run the script of the chart as the steps above show, and do not copy the statements of its files.
The comments of `database-roles.sql` tell what each step does and why.

Do not give either role more privileges.
The migrations need no superuser: `citext` and `btree_gist` are trusted extensions, and `pg_partman` needs only the owner of the database.

## Connect the release

1. Create the [PostgreSQL TLS Secret](./kubernetes#postgresql-tls-secret) with only `ca.crt`, the CA that signed the server certificate.
2. Add these values to your values file:

   ```yaml title="values.yaml"
   postgresql:
     bundled: false
     external:
       host: postgresql.example.internal
       port: 5432
       cidr: 10.20.30.40/32
     tls:
       existingSecret: hephaestus-postgresql-tls
   ```

   Set `host` to the name in the server certificate.
   Set `cidr` to the address range of the database. The network policy of the server opens only this range on `port`.

3. Install or upgrade the release.

The chart connects with `sslmode=verify-full`.
It has no setting for a connection without TLS or without certificate verification.

## Check that it worked

1. Read the log of the migration Job of the revision. It logs `Database migration completed`.
2. Run `kubectl --namespace hephaestus rollout status deployment/hephaestus-server`. The server becomes ready.
