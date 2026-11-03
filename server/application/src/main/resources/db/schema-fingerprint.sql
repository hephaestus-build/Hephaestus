-- One sorted line for each object that the changelog decides in the application schema: the schema fingerprint. Two
-- databases with the same lines have the same tables, columns, defaults, constraints, indexes, triggers, views,
-- functions, sequences, extensions, pg_partman parents and storage options, such as a table's fillfactor.
--
-- Left out, because no changelog decides them: Liquibase's own tables, pg_partman's runtime partitions, sequence
-- positions, the names of not-null constraints (a renamed column keeps its old one, and the column line says whether a
-- column is nullable), and everything that an extension owns (it moves with the extension's minor version, and the
-- extension line names the extension).
--
-- pg_dump and pg_upgrade restore an expression in a form that prints differently, so each prints as one form:
-- (ARRAY['a'::character varying])::text[] and ARRAY[('a'::character varying)::text] as ARRAY['a'], and
-- ((x >= a) AND (x <= b)) AND c, which BETWEEN leaves, as (x >= a) AND (x <= b) AND c.
-- Whitespace folds to one space, so each object is one line. SchemaFingerprint and scripts/release-upgrade-test.ts both
-- run this file.
WITH owned AS (
    SELECT objid FROM pg_depend WHERE deptype = 'e' AND classid IN ('pg_class'::regclass, 'pg_proc'::regclass)
)
SELECT line FROM (
SELECT regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(line,
    '\s+', ' ', 'g'),
    $$\('((?:[^']|'')*)'::character varying\)::text$$, $$'\1'$$, 'g'),
    $$'((?:[^']|'')*)'::character varying$$, $$'\1'$$, 'g'),
    $$\(ARRAY\[([^]]*)\]\)::text\[\]$$, $$ARRAY[\1]$$, 'g'),
    $$\(\(\(([a-z_]+) >= ([^ ]+)\) AND \(\1 <= ([^ ]+)\)\) AND $$, $$((\1 >= \2) AND (\1 <= \3) AND $$, 'g') AS line FROM (
    SELECT 'column|' || c.relname || '|' || a.attname || '|' || format_type(a.atttypid, a.atttypmod) || '|'
        || CASE WHEN a.attnotnull THEN 'not null' ELSE 'null' END || '|'
        || coalesce(pg_get_expr(d.adbin, d.adrelid), '') || '|' || a.attidentity::text || '|' || a.attgenerated::text AS line
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
        AND NOT c.relispartition AND a.attnum > 0 AND NOT a.attisdropped AND c.oid NOT IN (SELECT objid FROM owned)
    UNION ALL
    SELECT 'constraint|' || c.relname || '|' || k.conname || '|' || pg_get_constraintdef(k.oid)
    FROM pg_constraint k
    JOIN pg_class c ON c.oid = k.conrelid
    WHERE k.connamespace = 'public'::regnamespace AND NOT c.relispartition AND k.contype <> 'n'
        AND c.oid NOT IN (SELECT objid FROM owned)
    UNION ALL
    SELECT 'index|' || t.relname || '|' || i.relname || '|' || pg_get_indexdef(i.oid)
        || CASE WHEN x.indisvalid THEN '' ELSE '|invalid' END
    FROM pg_index x
    JOIN pg_class i ON i.oid = x.indexrelid
    JOIN pg_class t ON t.oid = x.indrelid
    WHERE i.relnamespace = 'public'::regnamespace AND NOT t.relispartition AND t.oid NOT IN (SELECT objid FROM owned)
    UNION ALL
    SELECT 'trigger|' || c.relname || '|' || g.tgname || '|' || pg_get_triggerdef(g.oid)
        || CASE g.tgenabled WHEN 'O' THEN '' ELSE '|enabled=' || g.tgenabled::text END
    FROM pg_trigger g
    JOIN pg_class c ON c.oid = g.tgrelid
    WHERE c.relnamespace = 'public'::regnamespace AND NOT g.tgisinternal AND NOT c.relispartition
    UNION ALL
    SELECT 'storage|' || c.relname || '|' || array_to_string(c.reloptions, ',')
    FROM pg_class c
    WHERE c.relnamespace = 'public'::regnamespace AND c.reloptions IS NOT NULL AND NOT c.relispartition
        AND c.oid NOT IN (SELECT objid FROM owned)
    UNION ALL
    SELECT 'view|' || c.relname || '|' || md5(pg_get_viewdef(c.oid))
    FROM pg_class c
    WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('v', 'm') AND c.oid NOT IN (SELECT objid FROM owned)
    UNION ALL
    SELECT 'function|' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')|'
        || md5(CASE WHEN p.prokind IN ('f', 'p') THEN pg_get_functiondef(p.oid) ELSE p.prosrc END)
    FROM pg_proc p
    WHERE p.pronamespace = 'public'::regnamespace AND p.oid NOT IN (SELECT objid FROM owned)
    UNION ALL
    SELECT 'sequence|' || s.sequencename || '|' || s.data_type || '|' || s.increment_by || '|' || s.min_value || '|'
        || s.max_value || '|' || s.cycle
    FROM pg_sequences s
    WHERE s.schemaname = 'public'
        AND to_regclass(quote_ident(s.schemaname) || '.' || quote_ident(s.sequencename)) NOT IN (SELECT objid FROM owned)
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
