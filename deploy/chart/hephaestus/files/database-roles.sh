# Sets up the database roles of Hephaestus (docs/admin/helm-chart.md § Database roles). Run it as the PostgreSQL
# administrator, with the passwords in the environment, and give it the connection options of psql:
#
#   DATABASE_OWNER_PASSWORD=… DATABASE_APP_PASSWORD=… sh database-roles.sh "host=… dbname=hephaestus user=…"
#
# It sends PostgreSQL only the SCRAM verifier of each password, which scram-sha-256.pl computes, and runs the quiet
# settings and the roles in one psql session. The chart runs it from a ConfigMap, never from a container command:
# Kubernetes rewrites $$ and $(NAME) in a command, which breaks the SQL.
set -eu
here=$(dirname "$0")
DATABASE_OWNER_VERIFIER=$(perl "$here/scram-sha-256.pl" DATABASE_OWNER_PASSWORD)
DATABASE_APP_VERIFIER=$(perl "$here/scram-sha-256.pl" DATABASE_APP_PASSWORD)
export DATABASE_OWNER_VERIFIER DATABASE_APP_VERIFIER
# psql needs only the verifiers: it does not inherit the passwords.
unset DATABASE_OWNER_PASSWORD DATABASE_APP_PASSWORD
exec psql --no-psqlrc --quiet --file="$here/database-quiet.sql" --file="$here/database-roles.sql" "$@"
