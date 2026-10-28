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

-- A result and its assignment must keep the same pinned version. Stop partial work explicitly
-- instead of changing that proof. The operator can request the remaining work after the upgrade.
CREATE TEMP TABLE source_contract_3_stopped_reviews AS
SELECT r.id, r.workspace_id
FROM review r
WHERE r.status IN ('QUEUED','RUNNING')
  AND EXISTS (
    SELECT 1 FROM review_practice rp JOIN practice_version v
      ON (v.id,v.practice_id,v.workspace_id)=(rp.practice_version_id,rp.practice_id,rp.workspace_id)
    WHERE (rp.review_id,rp.workspace_id)=(r.id,r.workspace_id)
      AND (v.definition#>>'{reads,contract}'='2.0.0' OR v.definition#>>'{scan,reads,contract}'='2.0.0'))
  AND (EXISTS (SELECT 1 FROM result x WHERE (x.review_id,x.workspace_id)=(r.id,r.workspace_id))
    OR EXISTS (SELECT 1 FROM review_item i WHERE (i.review_id,i.workspace_id)=(r.id,r.workspace_id) AND i.result_id IS NOT NULL)
    OR EXISTS (SELECT 1 FROM review_job j WHERE (j.review_id,j.workspace_id)=(r.id,r.workspace_id) AND j.output IS NOT NULL));

UPDATE review_job j SET
    status=CASE WHEN j.status IN ('QUEUED','RUNNING') THEN 'CANCELLED' ELSE j.status END,
    processing_status='DONE', cancellation_reason='CONTRACT_CHANGED', worker_id=NULL,
    admission_attempt=NULL, admission_digest=NULL, completed_at=COALESCE(j.completed_at,transaction_timestamp())
FROM source_contract_3_stopped_reviews s
WHERE (j.review_id,j.workspace_id)=(s.id,s.workspace_id)
  AND (j.status IN ('QUEUED','RUNNING') OR j.processing_status='PENDING');
UPDATE review_item i SET state='WITHHELD',reason='CONTRACT_CHANGED'
FROM source_contract_3_stopped_reviews s
WHERE (i.review_id,i.workspace_id)=(s.id,s.workspace_id) AND i.state='LISTED';
UPDATE review_practice rp SET status='WITHHELD',reason='CONTRACT_CHANGED'
FROM source_contract_3_stopped_reviews s
WHERE (rp.review_id,rp.workspace_id)=(s.id,s.workspace_id) AND rp.status IN ('QUEUED','READY','RUNNING');
UPDATE review r SET status='PARTIAL',stop_reason='CONTRACT_CHANGED',completed_at=transaction_timestamp()
FROM source_contract_3_stopped_reviews s WHERE (r.id,r.workspace_id)=(s.id,s.workspace_id);

-- Each executable historical pin gets its own successor. A later workspace edit must not
-- replace the definition that a queued review or an unfinished scan selected.
CREATE TEMP TABLE source_contract_3_pins (
    old_id bigint PRIMARY KEY,new_id bigint NOT NULL,practice_id bigint NOT NULL,workspace_id bigint NOT NULL);
DO $$
DECLARE old_version record; next_definition jsonb; next_id bigint;
BEGIN
    FOR old_version IN
        SELECT v.* FROM practice_version v
        WHERE EXISTS (SELECT 1 FROM practice p
            WHERE (p.current_version_id,p.id,p.workspace_id)=(v.id,v.practice_id,v.workspace_id))
        OR EXISTS (
            SELECT 1 FROM review_practice rp JOIN review r ON (r.id,r.workspace_id)=(rp.review_id,rp.workspace_id)
            WHERE (rp.practice_version_id,rp.practice_id,rp.workspace_id)=(v.id,v.practice_id,v.workspace_id)
              AND r.status IN ('QUEUED','RUNNING')
              AND (rp.status IN ('QUEUED','READY','RUNNING') OR EXISTS (
                SELECT 1 FROM review_item i WHERE (i.review_id,i.practice_id,i.workspace_id)=(rp.review_id,rp.practice_id,rp.workspace_id)
                  AND i.state='LISTED')))
        ORDER BY v.workspace_id,v.practice_id,v.number,v.id
    LOOP
        next_definition := pg_temp.source_contract_3_definition(old_version.definition);
        IF next_definition IS NOT DISTINCT FROM old_version.definition THEN CONTINUE; END IF;
        INSERT INTO practice_version(workspace_id,practice_id,number,definition,created_at)
        SELECT old_version.workspace_id,old_version.practice_id,COALESCE(max(v.number),0)+1,next_definition,transaction_timestamp()
        FROM practice_version v WHERE (v.practice_id,v.workspace_id)=(old_version.practice_id,old_version.workspace_id)
        RETURNING id INTO next_id;
        INSERT INTO source_contract_3_pins VALUES(old_version.id,next_id,old_version.practice_id,old_version.workspace_id);
    END LOOP;
END $$;
UPDATE practice p SET current_version_id=m.new_id,updated_at=transaction_timestamp()
FROM source_contract_3_pins m
WHERE (p.current_version_id,p.id,p.workspace_id)=(m.old_id,m.practice_id,m.workspace_id);
UPDATE review_practice rp SET practice_version_id=m.new_id,capture_decision=NULL
FROM source_contract_3_pins m,review r
WHERE (rp.practice_version_id,rp.practice_id,rp.workspace_id)=(m.old_id,m.practice_id,m.workspace_id)
  AND (r.id,r.workspace_id)=(rp.review_id,rp.workspace_id) AND r.status IN ('QUEUED','RUNNING')
  AND (rp.status IN ('QUEUED','READY','RUNNING') OR EXISTS (
    SELECT 1 FROM review_item i WHERE (i.review_id,i.practice_id,i.workspace_id)=(rp.review_id,rp.practice_id,rp.workspace_id)
      AND i.state='LISTED'));
-- All writers and sandboxes are stopped. Recapture under the new pin without spending a retry.
UPDATE review_job j SET status='QUEUED',processing_status='NONE',capture=NULL,worker_id=NULL,
    admission_attempt=NULL,admission_digest=NULL,available_at=transaction_timestamp(),hold_reason=NULL,
    started_at=NULL,execution_started_at=NULL,completed_at=NULL,cancellation_reason=NULL
WHERE j.status IN ('QUEUED','RUNNING') AND EXISTS (
    SELECT 1 FROM review_practice rp JOIN source_contract_3_pins m
      ON (rp.practice_version_id,rp.practice_id,rp.workspace_id)=(m.new_id,m.practice_id,m.workspace_id)
    JOIN review r ON (r.id,r.workspace_id)=(rp.review_id,rp.workspace_id)
    WHERE (rp.review_id,rp.workspace_id)=(j.review_id,j.workspace_id) AND r.status IN ('QUEUED','RUNNING'));
DROP TABLE source_contract_3_pins;
DROP TABLE source_contract_3_stopped_reviews;

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
    ) OR EXISTS (
        SELECT 1 FROM review_practice rp JOIN review r ON (r.id,r.workspace_id)=(rp.review_id,rp.workspace_id)
        JOIN practice_version v ON (v.id,v.practice_id,v.workspace_id)=(rp.practice_version_id,rp.practice_id,rp.workspace_id)
        WHERE r.status IN ('QUEUED','RUNNING')
          AND (rp.status IN ('QUEUED','READY','RUNNING') OR EXISTS (
            SELECT 1 FROM review_item i WHERE (i.review_id,i.practice_id,i.workspace_id)=(rp.review_id,rp.practice_id,rp.workspace_id)
              AND i.state='LISTED'))
          AND (v.definition#>>'{reads,contract}'='2.0.0' OR v.definition#>>'{scan,reads,contract}'='2.0.0')
    ) THEN RAISE EXCEPTION 'An executable source contract 2.0.0 pin remains'; END IF;
END $$;
DROP FUNCTION pg_temp.source_contract_3_definition(jsonb);
DROP FUNCTION pg_temp.source_contract_3_reads(jsonb);
