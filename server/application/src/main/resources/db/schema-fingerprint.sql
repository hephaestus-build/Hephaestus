-- One sorted line for each object that the changelog decides in the application schema: the schema fingerprint.
-- Two databases with the same lines have the same tables, columns, defaults, constraints, indexes, triggers, views,
-- functions, sequences, extensions and pg_partman parents. Liquibase's own tables, pg_partman's runtime partitions and
-- sequence positions are left out: no changelog decides them. Whitespace folds to one space, so each object is one
-- line. SchemaFingerprint and scripts/release-upgrade-test.ts
-- both run this file.
SELECT line FROM (
SELECT regexp_replace(line, '\s+', ' ', 'g') AS line FROM (
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
    WHERE k.connamespace = 'public'::regnamespace AND NOT c.relispartition
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
    SELECT 'view|' || c.relname || '|' || md5(pg_get_viewdef(c.oid))
    FROM pg_class c
    WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('v', 'm')
    UNION ALL
    SELECT 'function|' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')|' || md5(p.prosrc)
    FROM pg_proc p
    WHERE p.pronamespace = 'public'::regnamespace
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
