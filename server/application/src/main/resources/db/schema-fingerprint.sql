-- One sorted line for each object that the changelog decides in the application schema: the schema fingerprint. Two
-- databases with the same lines have the same tables, columns, defaults, constraints, indexes, triggers, views,
-- functions, sequences, extensions, pg_partman parents and storage options, such as a table's fillfactor. Liquibase's
-- own tables, pg_partman's runtime partitions, sequence positions and the functions that an extension owns are left
-- out: no changelog decides them, and an extension's functions move with its minor version. So are the names of
-- not-null constraints: a renamed column keeps its old one. The column line says whether a column is nullable.
-- PostgreSQL stores one list of varchar literals in two forms, and pg_dump's output turns the first into the second, so
-- both print as one form: (ARRAY['a'::character varying])::text[] and ARRAY[('a'::character varying)::text].
-- Whitespace folds to one space, so each object is one line. SchemaFingerprint and scripts/release-upgrade-test.ts both
-- run this file.
SELECT line FROM (
SELECT regexp_replace(regexp_replace(regexp_replace(regexp_replace(line,
    '\s+', ' ', 'g'),
    $$\('((?:[^']|'')*)'::character varying\)::text$$, $$'\1'$$, 'g'),
    $$'((?:[^']|'')*)'::character varying$$, $$'\1'$$, 'g'),
    $$\(ARRAY\[([^]]*)\]\)::text\[\]$$, $$ARRAY[\1]$$, 'g') AS line FROM (
    SELECT 'column|' || c.relname || '|' || a.attname || '|' || format_type(a.atttypid, a.atttypmod) || '|'
        || CASE WHEN a.attnotnull THEN 'not null' ELSE 'null' END || '|'
        || coalesce(pg_get_expr(d.adbin, d.adrelid), '') || '|' || a.attidentity::text || '|' || a.attgenerated::text AS line
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
        AND NOT c.relispartition AND a.attnum > 0 AND NOT a.attisdropped
    UNION ALL
    SELECT 'constraint|' || c.relname || '|' || k.conname || '|' || pg_get_constraintdef(k.oid)
    FROM pg_constraint k
    JOIN pg_class c ON c.oid = k.conrelid
    WHERE k.connamespace = 'public'::regnamespace AND NOT c.relispartition AND k.contype <> 'n'
    UNION ALL
    SELECT 'index|' || t.relname || '|' || i.relname || '|' || pg_get_indexdef(i.oid)
    FROM pg_index x
    JOIN pg_class i ON i.oid = x.indexrelid
    JOIN pg_class t ON t.oid = x.indrelid
    WHERE i.relnamespace = 'public'::regnamespace AND NOT t.relispartition
    UNION ALL
    SELECT 'trigger|' || c.relname || '|' || g.tgname || '|' || pg_get_triggerdef(g.oid)
    FROM pg_trigger g
    JOIN pg_class c ON c.oid = g.tgrelid
    WHERE c.relnamespace = 'public'::regnamespace AND NOT g.tgisinternal AND NOT c.relispartition
    UNION ALL
    SELECT 'storage|' || c.relname || '|' || array_to_string(c.reloptions, ',')
    FROM pg_class c
    WHERE c.relnamespace = 'public'::regnamespace AND c.reloptions IS NOT NULL AND NOT c.relispartition
    UNION ALL
    SELECT 'view|' || c.relname || '|' || md5(pg_get_viewdef(c.oid))
    FROM pg_class c
    WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('v', 'm')
    UNION ALL
    SELECT 'function|' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')|' || md5(p.prosrc)
    FROM pg_proc p
    WHERE p.pronamespace = 'public'::regnamespace
        AND NOT EXISTS (SELECT 1 FROM pg_depend d
            WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
    UNION ALL
    SELECT 'sequence|' || s.sequencename || '|' || s.data_type || '|' || s.increment_by || '|' || s.min_value || '|'
        || s.max_value || '|' || s.cycle
    FROM pg_sequences s
    WHERE s.schemaname = 'public'
    UNION ALL
    SELECT 'extension|' || e.extname || '|' || n.nspname || '|' || split_part(e.extversion, '.', 1)
    FROM pg_extension e
    JOIN pg_namespace n ON n.oid = e.extnamespace
    WHERE e.extname <> 'plpgsql'
    UNION ALL
    SELECT 'partman|' || p.parent_table || '|' || p.control || '|' || p.partition_interval || '|' || p.premake
    FROM partman.part_config p
) facts
WHERE line NOT LIKE '%databasechangelog%'
) folded
ORDER BY line COLLATE "C"
