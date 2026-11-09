# Sets up the login of the PostgreSQL exporter (docs/admin/observability.mdx § Database metrics). Run it as the
# PostgreSQL administrator, with the password in the environment, and give it the connection options of psql:
#
#   DATABASE_MONITOR_PASSWORD=… sh database-monitor.sh "host=… dbname=hephaestus user=…"
#
# It sends PostgreSQL only the SCRAM verifier of the password, which scram-sha-256.pl computes, and runs the quiet
# settings and the monitor role in one psql session. The chart runs it from a ConfigMap, as database-roles.sh.
set -eu
here=$(dirname "$0")
DATABASE_MONITOR_VERIFIER=$(perl "$here/scram-sha-256.pl" DATABASE_MONITOR_PASSWORD)
export DATABASE_MONITOR_VERIFIER
exec psql --no-psqlrc --quiet --file="$here/database-quiet.sql" --file="$here/database-monitor.sql" "$@"
