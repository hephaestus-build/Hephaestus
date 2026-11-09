-- The database roles of Hephaestus (docs/admin/kubernetes.mdx § Database roles). Run it as the PostgreSQL
-- administrator, connected to the Hephaestus database, before each migration:
--
--   psql -v ON_ERROR_STOP=1 -v owner_password=… -v app_password=… -f database-roles.sql
--
-- hephaestus_owner owns the database, the schema objects and runs the migration. hephaestus_app reads and writes
-- the rows of the server and the worker, and owns nothing. Neither is a superuser, creates roles or bypasses
-- row-level security. Each statement is idempotent, and the passwords are set each time, so a changed password
-- takes effect with the next run.
\set ON_ERROR_STOP on

SELECT 'CREATE ROLE hephaestus_owner' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hephaestus_owner') \gexec
SELECT 'CREATE ROLE hephaestus_app' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hephaestus_app') \gexec
ALTER ROLE hephaestus_owner WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'owner_password';
ALTER ROLE hephaestus_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';

BEGIN;

-- The database owner owns the schema public (pg_database_owner) and may create the schema partman.
SELECT format('ALTER DATABASE %I OWNER TO hephaestus_owner', current_database()) \gexec

-- A database that another role migrated, such as the administrator before 1.0, moves to the owner. REASSIGN OWNED
-- cannot do this: it refuses the bootstrap superuser, which owns the system catalogs. The objects of an extension
-- stay with the role that created it. A sequence that a column owns moves with its table.
DO $$
DECLARE
    statement text;
BEGIN
    FOR statement IN
        SELECT format('ALTER SCHEMA %I OWNER TO hephaestus_owner', n.nspname)
        FROM pg_namespace n
        WHERE n.nspname = 'partman' AND n.nspowner <> 'hephaestus_owner'::regrole
        UNION ALL
        SELECT format('ALTER %s %s OWNER TO hephaestus_owner',
                      CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' WHEN 'S' THEN 'SEQUENCE'
                                     WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END,
                      c.oid::regclass)
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname IN ('public', 'partman') AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
          AND c.relowner <> 'hephaestus_owner'::regrole
          AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                          AND (d.deptype = 'e' OR (c.relkind = 'S' AND d.deptype IN ('a', 'i') AND d.refobjsubid > 0)))
        UNION ALL
        SELECT format('ALTER ROUTINE %s OWNER TO hephaestus_owner', p.oid::regprocedure)
        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname IN ('public', 'partman') AND p.proowner <> 'hephaestus_owner'::regrole
          AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid
                          AND d.deptype = 'e')
    LOOP
        EXECUTE statement;
    END LOOP;
END
$$;

-- pg_partman keeps its configuration in tables of the extension. A fresh database gets the schema with the migration.
SELECT 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA partman TO hephaestus_owner'
WHERE EXISTS (SELECT FROM pg_namespace WHERE nspname = 'partman') \gexec

-- The rows, for the objects that exist and for each one that the owner creates later. Functions keep PUBLIC's
-- EXECUTE unless the migration revokes it, as it does for the partition maintenance of auth_event.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO hephaestus_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO hephaestus_app;
GRANT EXECUTE ON ALL ROUTINES IN SCHEMA public TO hephaestus_app;
ALTER DEFAULT PRIVILEGES FOR ROLE hephaestus_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hephaestus_app;
ALTER DEFAULT PRIVILEGES FOR ROLE hephaestus_owner IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO hephaestus_app;
ALTER DEFAULT PRIVILEGES FOR ROLE hephaestus_owner IN SCHEMA public
    GRANT EXECUTE ON ROUTINES TO hephaestus_app;

COMMIT;
