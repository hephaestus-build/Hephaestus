-- The database roles of Hephaestus (docs/admin/helm-chart.md § Database roles). Run it as the PostgreSQL
-- administrator, connected to the Hephaestus database, before each migration, after database-quiet.sql in the same
-- session. It takes the SCRAM-SHA-256 verifier of each password from the environment of psql, never a password:
--
--   export DATABASE_OWNER_VERIFIER="$(perl scram-sha-256.pl DATABASE_OWNER_PASSWORD)"
--   export DATABASE_APP_VERIFIER="$(perl scram-sha-256.pl DATABASE_APP_PASSWORD)"
--   psql -f database-quiet.sql -f database-roles.sql
--
-- hephaestus_owner owns the database, the schema objects and runs the migration. hephaestus_app reads and writes
-- the rows of the server and the worker, and owns nothing. Neither is a superuser, creates roles, replicates or has
-- BYPASSRLS. As the owner of its tables, hephaestus_owner is still not held to their policies unless a table forces
-- them. The administrator may be a superuser, or a role with CREATEROLE and CREATEDB, as a managed PostgreSQL service
-- gives. Each statement is idempotent, and the verifiers are set each time, so a changed password takes effect with
-- the next run.
\set ON_ERROR_STOP on
\if :{?hephaestus_quiet}
\else
DO $$ BEGIN RAISE EXCEPTION 'database-roles.sql sets verifiers: run database-quiet.sql first, in the same psql session'; END $$;
\endif

\getenv owner_verifier DATABASE_OWNER_VERIFIER
\getenv app_verifier DATABASE_APP_VERIFIER
\if :{?owner_verifier}
\else
DO $$ BEGIN RAISE EXCEPTION 'database-roles.sql needs DATABASE_OWNER_VERIFIER in the environment of psql, from scram-sha-256.pl'; END $$;
\endif
\if :{?app_verifier}
\else
DO $$ BEGIN RAISE EXCEPTION 'database-roles.sql needs DATABASE_APP_VERIFIER in the environment of psql, from scram-sha-256.pl'; END $$;
\endif
-- PostgreSQL stores any other value as a password, which it then hashes itself: only a verifier may pass.
SELECT :'owner_verifier' ~ '^SCRAM-SHA-256\$[0-9]+:[A-Za-z0-9+/]+=*\$[A-Za-z0-9+/]+=*:[A-Za-z0-9+/]+=*$' AND :'app_verifier' ~ '^SCRAM-SHA-256\$[0-9]+:[A-Za-z0-9+/]+=*\$[A-Za-z0-9+/]+=*:[A-Za-z0-9+/]+=*$' AS hephaestus_verifiers \gset
\if :hephaestus_verifiers
\else
DO $$ BEGIN RAISE EXCEPTION 'database-roles.sql takes SCRAM-SHA-256 verifiers, never a password: compute them with scram-sha-256.pl'; END $$;
\endif

-- CREATE ROLE gives neither role an attribute. Only a superuser could take one away, so a role that has one stops
-- the run instead.
SELECT 'CREATE ROLE hephaestus_owner' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hephaestus_owner') \gexec
SELECT 'CREATE ROLE hephaestus_app' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hephaestus_app') \gexec
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname IN ('hephaestus_owner', 'hephaestus_app')
               AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolreplication)) THEN
        RAISE EXCEPTION 'hephaestus_owner and hephaestus_app must not be superusers, bypass row-level security, create roles or replicate'
            USING HINT = 'As a superuser, ALTER ROLE them NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOREPLICATION.';
    END IF;
END
$$;
ALTER ROLE hephaestus_owner WITH LOGIN PASSWORD :'owner_verifier';
ALTER ROLE hephaestus_app WITH LOGIN PASSWORD :'app_verifier';

BEGIN;

-- An administrator that is no superuser acts as the owner: it gives the database and the objects to that role, and
-- grants and updates the extensions as that role.
SELECT 'GRANT hephaestus_owner TO CURRENT_USER WITH INHERIT TRUE, SET TRUE'
WHERE NOT (pg_has_role('hephaestus_owner', 'SET') AND pg_has_role('hephaestus_owner', 'USAGE')) \gexec

-- The database owner may create the schema partman. From PostgreSQL 15 on, it also owns the schema public, through
-- pg_database_owner.
SELECT format('ALTER DATABASE %I OWNER TO hephaestus_owner', current_database()) \gexec

-- A database created before PostgreSQL 15 keeps public with its old owner, and lets every role create objects there.
-- The application role must not: an object that it plants there would resolve in a later migration of the owner.
-- The owner keeps CREATE through the grant, whoever owns public.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO hephaestus_owner;

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

-- The extensions of the schema follow the PostgreSQL image: the migrations never update one. A database that the
-- administrator migrated before 1.0 keeps them with the administrator, which may update them here.
SELECT format('ALTER EXTENSION %I UPDATE', e.extname)
FROM pg_extension e JOIN pg_available_extensions a ON a.name = e.extname
WHERE e.extname IN ('pg_partman', 'citext', 'btree_gist') AND e.extversion IS DISTINCT FROM a.default_version \gexec

-- pg_partman keeps its configuration in tables of the extension. A fresh database gets the schema with the migration.
SELECT 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA partman TO hephaestus_owner'
WHERE EXISTS (SELECT FROM pg_namespace WHERE nspname = 'partman') \gexec

-- The rows, for the objects that exist and for each one that the owner creates later. Functions keep PUBLIC's
-- EXECUTE unless the migration revokes it, as it does for the partition maintenance of auth_event.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO hephaestus_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO hephaestus_app;
-- The routines of the schema only: those of an extension keep PUBLIC's EXECUTE, and a grant on them by an
-- administrator that does not own them only warns.
SELECT format('GRANT EXECUTE ON ROUTINE %s TO hephaestus_app', p.oid::regprocedure)
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND p.proowner = 'hephaestus_owner'::regrole
  AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e') \gexec
ALTER DEFAULT PRIVILEGES FOR ROLE hephaestus_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO hephaestus_app;
ALTER DEFAULT PRIVILEGES FOR ROLE hephaestus_owner IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO hephaestus_app;
ALTER DEFAULT PRIVILEGES FOR ROLE hephaestus_owner IN SCHEMA public
    GRANT EXECUTE ON ROUTINES TO hephaestus_app;

COMMIT;
