-- The runtime accepts one catalog. Current definitions and their comparison bases must move together.
-- Historical versions remain immutable; the new current version keeps the author's criteria and script.
CREATE FUNCTION pg_temp.source_contract_3_reads(old_reads jsonb) RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE sources jsonb; kind text;
BEGIN
    IF old_reads IS NULL OR old_reads->>'contract' IS DISTINCT FROM '2.0.0' THEN RETURN old_reads; END IF;
    sources := old_reads->'sources';
    IF jsonb_typeof(sources) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'A source contract 2.0.0 pin has invalid sources';
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(sources) s WHERE s->>'source'='scm.pull-request.core') THEN
        FOREACH kind IN ARRAY ARRAY['scm.pull-request.checks','scm.review-threads'] LOOP
            IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(sources) s WHERE s->>'source'=kind) THEN
                sources := sources || jsonb_build_array(jsonb_build_object('source',kind,'need','OPTIONAL'));
            END IF;
        END LOOP;
    END IF;
    RETURN jsonb_set(jsonb_set(old_reads,'{contract}','"3.0.0"'::jsonb),'{sources}',sources);
END $$;

CREATE FUNCTION pg_temp.source_contract_3_definition(old_definition jsonb) RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE upgraded jsonb := old_definition;
BEGIN
    IF old_definition IS NULL THEN RETURN NULL; END IF;
    IF old_definition#>>'{reads,contract}'='2.0.0' THEN
        upgraded := jsonb_set(upgraded,'{reads}',pg_temp.source_contract_3_reads(upgraded->'reads'));
    END IF;
    IF old_definition#>>'{scan,reads,contract}'='2.0.0' THEN
        upgraded := jsonb_set(upgraded,'{scan,reads}',pg_temp.source_contract_3_reads(upgraded#>'{scan,reads}'));
    END IF;
    RETURN upgraded;
END $$;

WITH upgraded AS (
    SELECT p.id, p.workspace_id, v.definition,
           pg_temp.source_contract_3_definition(v.definition) AS next_definition
    FROM practice p JOIN practice_version v
      ON (v.id,v.practice_id,v.workspace_id)=(p.current_version_id,p.id,p.workspace_id)
    WHERE v.definition#>>'{reads,contract}'='2.0.0'
       OR v.definition#>>'{scan,reads,contract}'='2.0.0'
), inserted AS (
    INSERT INTO practice_version(workspace_id,practice_id,number,definition,created_at)
    SELECT u.workspace_id,u.id,
           (SELECT max(v.number)+1 FROM practice_version v WHERE v.practice_id=u.id AND v.workspace_id=u.workspace_id),
           u.next_definition,transaction_timestamp()
    FROM upgraded u WHERE u.next_definition IS DISTINCT FROM u.definition
    RETURNING id,practice_id,workspace_id
)
UPDATE practice p SET current_version_id=i.id,updated_at=transaction_timestamp()
FROM inserted i WHERE (p.id,p.workspace_id)=(i.practice_id,i.workspace_id);

UPDATE practice SET catalog_base=jsonb_set(catalog_base,'{definition}',
    pg_temp.source_contract_3_definition(catalog_base->'definition'))
WHERE catalog_base IS NOT NULL;

UPDATE catalog_entry SET
    definition=CASE WHEN definition IS NULL THEN NULL ELSE jsonb_set(definition,'{definition}',
        pg_temp.source_contract_3_definition(definition->'definition')) END,
    base=CASE WHEN base IS NULL THEN NULL ELSE jsonb_set(base,'{definition}',
        pg_temp.source_contract_3_definition(base->'definition')) END
WHERE entry_type='PRACTICE';

DO $$ BEGIN
    IF EXISTS (
        SELECT 1 FROM practice p JOIN practice_version v
          ON (v.id,v.practice_id,v.workspace_id)=(p.current_version_id,p.id,p.workspace_id)
        WHERE v.definition#>>'{reads,contract}'='2.0.0' OR v.definition#>>'{scan,reads,contract}'='2.0.0'
           OR p.catalog_base#>>'{definition,reads,contract}'='2.0.0'
           OR p.catalog_base#>>'{definition,scan,reads,contract}'='2.0.0'
    ) OR EXISTS (
        SELECT 1 FROM catalog_entry WHERE entry_type='PRACTICE' AND (
            definition#>>'{definition,reads,contract}'='2.0.0' OR definition#>>'{definition,scan,reads,contract}'='2.0.0'
            OR base#>>'{definition,reads,contract}'='2.0.0' OR base#>>'{definition,scan,reads,contract}'='2.0.0')
    ) THEN RAISE EXCEPTION 'A current source contract 2.0.0 pin remains'; END IF;
END $$;
DROP FUNCTION pg_temp.source_contract_3_definition(jsonb);
DROP FUNCTION pg_temp.source_contract_3_reads(jsonb);
