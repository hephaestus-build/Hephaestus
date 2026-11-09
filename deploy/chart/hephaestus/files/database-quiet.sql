-- Keeps the passwords that the next file of the same psql session sets out of the statement statistics and the
-- server log (docs/admin/kubernetes.mdx § Database roles). Run it first, in that session:
--
--   psql -f database-quiet.sql -f database-roles.sql
--
-- database-roles.sql and database-monitor.sql refuse to run without it. A role that may not change a setting stops
-- here when the setting would record a password, and names it. It needs psql 15, for \getenv, and PostgreSQL 16, for
-- the role memberships of database-roles.sql.
\set ON_ERROR_STOP on

SELECT :VERSION_NUM >= 150000 AS hephaestus_psql_current,
       current_setting('server_version_num')::integer >= 160000 AS hephaestus_server_current \gset
\if :hephaestus_psql_current
\else
DO $$ BEGIN RAISE EXCEPTION 'database-quiet.sql needs psql 15 or later, which reads the passwords from the environment'; END $$;
\endif
\if :hephaestus_server_current
\else
DO $$ BEGIN RAISE EXCEPTION 'database-quiet.sql needs PostgreSQL 16 or later'; END $$;
\endif

DO $$
DECLARE
    setting record;
BEGIN
    FOR setting IN
        SELECT * FROM (VALUES ('pg_stat_statements.track_utility', 'off'), ('log_statement', 'none'),
                              ('log_min_duration_statement', '-1'), ('log_min_duration_sample', '-1'),
                              ('log_min_error_statement', 'panic')) AS quiet (name, value)
    LOOP
        BEGIN
            PERFORM set_config(setting.name, setting.value, false);
        EXCEPTION WHEN insufficient_privilege THEN
            IF current_setting(setting.name, true) IS NOT DISTINCT FROM setting.value THEN
                NULL;
            ELSIF setting.name = 'log_min_error_statement' THEN
                RAISE WARNING 'database-quiet.sql: % stays %: a failed statement of this session writes its password to the server log',
                    setting.name, current_setting(setting.name)
                    USING HINT = format('Set %s = %s for this role at the server to avoid this.', setting.name, setting.value);
            ELSE
                RAISE EXCEPTION 'database-quiet.sql: % is % and % may not change it, so a password would be recorded',
                    setting.name, current_setting(setting.name), current_user
                    USING HINT = format('Set %s = %s for this role at the server, or grant it SET on the parameter, and run again.',
                                        setting.name, setting.value);
            END IF;
        END;
    END LOOP;
END
$$;

\set hephaestus_quiet true
