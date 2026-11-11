---
title: Use an external PostgreSQL and NATS
description: Set up the database roles on your own PostgreSQL, and connect the release to it and to your own NATS JetStream broker.
page_type: how-to
---

By default, the chart runs its own PostgreSQL and NATS.
Use this procedure to run the release against a PostgreSQL server or a NATS broker that you operate.
[Database roles](./helm-chart#database-roles) describes each role that the release uses.

## Set up the database roles

The chart never gets the administrator login of your PostgreSQL.
Thus, you set up the roles yourself.
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
  Thus, it can give the database and the objects to that role.
  The administrator can then act as `hephaestus_owner`, and the row-level security of its tables does not apply to it.
  This gives it nothing new: with the `ADMIN` option on the role, it could already grant itself that membership.

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

Set `postgresql.bundled=false` to use your PostgreSQL server.
Set up its [database roles](#set-up-the-database-roles) first.
That server must have the `pg_partman` extension.
Also set `wal_compression = lz4` (or `zstd`) on it, as the bundled PostgreSQL does.
Without it, a streamed answer writes about 1.5 times more write-ahead log, because most of that log is full-page images of index pages.
Compression costs a little CPU for each full-page image.

The chart connects to `postgresql.external.host` and `postgresql.external.port` with `sslmode=verify-full`.
The server certificate must name that host.
The Secret in `postgresql.tls.existingSecret` needs only `ca.crt`, the CA that signed the server certificate.
The chart has no setting for a connection without certificate verification or without TLS.

Set `nats.bundled=false` to use your NATS JetStream broker.
The broker must run NATS Server 2.11 or later: a replay pauses a consumer.
On an older broker, the server starts and delivers events, but it logs `integration.broker.unsupported` at start, and the `natsBroker` health component is `DOWN`.
Put the broker on a private network that only this release can reach.

For a `tls://` broker, Hephaestus verifies the certificate chain and the broker's hostname.
The certificate must name the host in `nats.external.url`.
For each advertised address, the destination broker's certificate must name that address.

The bundled broker stays inside the release namespace, and network policy admits only the server and the webhook role.
The application has no setting for the stream replica count.
A three-server JetStream cluster therefore keeps one replica of each stream.

## Check that it worked

1. Install or upgrade the release.
2. Read the log of the migration Job of the revision. It logs `Database migration completed`.
3. Run `kubectl --namespace hephaestus rollout status deployment/hephaestus-server`. The server becomes ready.
