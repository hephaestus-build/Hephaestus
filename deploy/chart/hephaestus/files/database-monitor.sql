-- The login of the PostgreSQL exporter (docs/admin/monitoring.md § Turn on database metrics). Run it as the PostgreSQL
-- administrator, connected to the Hephaestus database, after database-quiet.sql in the same session. It takes the
-- SCRAM-SHA-256 verifier of the password from the environment of psql, never the password:
--
--   export DATABASE_MONITOR_VERIFIER="$(perl scram-sha-256.pl DATABASE_MONITOR_PASSWORD)"
--   psql -f database-quiet.sql -f database-monitor.sql
--
-- hephaestus_monitor is a member of pg_monitor only. The server must preload pg_stat_statements. The extension goes
-- in a schema of its own, so the application's schema stays unchanged. Each statement is idempotent, and the
-- verifier is set each time.
\set ON_ERROR_STOP on
\if :{?hephaestus_quiet}
\else
DO $$ BEGIN RAISE EXCEPTION 'database-monitor.sql sets a verifier: run database-quiet.sql first, in the same psql session'; END $$;
\endif

\getenv monitor_verifier DATABASE_MONITOR_VERIFIER
\if :{?monitor_verifier}
\else
DO $$ BEGIN RAISE EXCEPTION 'database-monitor.sql needs DATABASE_MONITOR_VERIFIER in the environment of psql, from scram-sha-256.pl'; END $$;
\endif
-- PostgreSQL stores any other value as a password, which it then hashes itself: only a verifier may pass.
SELECT :'monitor_verifier' ~ '^SCRAM-SHA-256\$[0-9]+:[A-Za-z0-9+/]+=*\$[A-Za-z0-9+/]+=*:[A-Za-z0-9+/]+=*$' AS hephaestus_verifier \gset
\if :hephaestus_verifier
\else
DO $$ BEGIN RAISE EXCEPTION 'database-monitor.sql takes a SCRAM-SHA-256 verifier, never a password: compute it with scram-sha-256.pl'; END $$;
\endif

SELECT 'CREATE ROLE hephaestus_monitor' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hephaestus_monitor') \gexec
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'hephaestus_monitor'
               AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication)) THEN
        RAISE EXCEPTION 'hephaestus_monitor must hold no role attribute: it reads statistics only'
            USING HINT = 'As a superuser, ALTER ROLE hephaestus_monitor NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB NOREPLICATION.';
    END IF;
END
$$;
ALTER ROLE hephaestus_monitor WITH LOGIN CONNECTION LIMIT 2 PASSWORD :'monitor_verifier';
GRANT pg_monitor TO hephaestus_monitor;
CREATE SCHEMA IF NOT EXISTS monitoring;
GRANT USAGE ON SCHEMA monitoring TO hephaestus_monitor;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements SCHEMA monitoring;
ALTER ROLE hephaestus_monitor SET search_path = monitoring, public;
