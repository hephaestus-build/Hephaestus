-- The schema that the published 1.0.0-rc.1 image built on an empty database, as pg_dump writes it.
-- Functions come before the tables they read. The change set's transaction ends this setting.
SET LOCAL check_function_bodies = false;

CREATE SCHEMA partman;

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public;

CREATE EXTENSION IF NOT EXISTS pg_partman WITH SCHEMA partman;

CREATE FUNCTION public.check_result_children(p_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE r result%ROWTYPE; n bigint; worst integer; expected varchar(16);
BEGIN
  SELECT * INTO r FROM result WHERE id=p_id FOR NO KEY UPDATE;
  IF NOT FOUND THEN RETURN; END IF;
  SELECT count(*),max(CASE severity WHEN 'CRITICAL' THEN 3 WHEN 'MAJOR' THEN 2
                       WHEN 'MINOR' THEN 1 WHEN 'TRIVIAL' THEN 0 END)
    INTO n,worst FROM problem WHERE result_id=p_id;
  IF (r.outcome='NOT_MET') <> (n>0) THEN
    RAISE EXCEPTION 'result % has invalid problem count',p_id USING ERRCODE='23514';
  END IF;
  IF r.evidence_version=1 AND EXISTS(SELECT 1 FROM problem
       WHERE result_id=p_id AND (basis IS NULL OR jsonb_array_length(citations)=0)) THEN
    RAISE EXCEPTION 'result % has incomplete problem proof',p_id USING ERRCODE='23514';
  END IF;
  expected := CASE worst WHEN 3 THEN 'CRITICAL' WHEN 2 THEN 'MAJOR'
                       WHEN 1 THEN 'MINOR' WHEN 0 THEN 'TRIVIAL' END;
  IF r.severity IS DISTINCT FROM expected THEN
    UPDATE result SET severity=expected WHERE id=p_id;
  END IF;
END $$;

CREATE FUNCTION public.check_review_item_scope(p_review uuid, p_practice bigint, p_item bigint) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE a review_item%ROWTYPE; v review%ROWTYPE; r result%ROWTYPE; pinned bigint;
BEGIN
 SELECT * INTO a FROM review_item WHERE review_id=p_review AND practice_id=p_practice AND item_id=p_item;
 IF NOT FOUND THEN RETURN; END IF;
 SELECT * INTO v FROM review WHERE id=a.review_id AND workspace_id=a.workspace_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'assignment review is outside workspace' USING ERRCODE='23503'; END IF;
 IF (v.kind='ITEM' AND a.item_id<>v.item_id) OR (v.kind='SCAN' AND NOT EXISTS(
   SELECT 1 FROM item i JOIN review_practice p
     ON p.review_id=a.review_id AND p.practice_id=a.practice_id AND p.workspace_id=a.workspace_id
     JOIN practice_version pv ON pv.id=p.practice_version_id AND pv.practice_id=p.practice_id AND pv.workspace_id=p.workspace_id
     WHERE i.id=a.item_id AND i.workspace_id=a.workspace_id
       AND i.kind=(pv.definition->'scan'->>'itemKind') AND i.parent_id=v.item_id)) THEN
  RAISE EXCEPTION 'assignment is outside review scope' USING ERRCODE='23514'; END IF;
 IF a.job_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM review_job j WHERE j.id=a.job_id
   AND j.workspace_id=a.workspace_id AND j.review_id=a.review_id) THEN
  RAISE EXCEPTION 'assignment job belongs to another review' USING ERRCODE='23514'; END IF;
 IF a.result_id IS NOT NULL THEN
  SELECT * INTO r FROM result WHERE id=a.result_id AND workspace_id=a.workspace_id;
  SELECT practice_version_id INTO pinned FROM review_practice WHERE review_id=a.review_id
    AND practice_id=a.practice_id AND workspace_id=a.workspace_id;
  IF r.item_version IS DISTINCT FROM a.item_version OR r.practice_version_id IS DISTINCT FROM pinned
    OR (a.state='JUDGED' AND r.review_id<>a.review_id) THEN
   RAISE EXCEPTION 'assignment result differs from frozen work/practice' USING ERRCODE='23514'; END IF;
 END IF;
END $$;

CREATE FUNCTION public.config_audit_event_block_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
            BEGIN
                IF TG_OP = 'DELETE' THEN
                    -- Retention is the ONLY sanctioned delete (ConfigAuditRetentionJob, 365 days).
                    -- Keeping the window here rather than blocking DELETE outright is what lets this
                    -- table stay unpartitioned while still honouring storage limitation (GDPR Art.
                    -- 5(1)(e)). Inside the window the row is untouchable.
                    IF OLD.occurred_at >= now() - interval '365 days' THEN
                        RAISE EXCEPTION 'config_audit_event is append-only: DELETE is only permitted past the 365-day retention window';
                    END IF;
                    RETURN OLD;
                END IF;
                -- Permit ONLY erasure, per column: each redactable column may stay or go to NULL. Per-column
            -- (not all-at-once) so an FK's ON DELETE SET NULL, which touches one column, does not trip
            -- this. Comparing to_jsonb(NEW) minus the redactable keys covers future columns with no edit here.

                IF (NEW.actor_account_id IS NULL OR NEW.actor_account_id = OLD.actor_account_id)
                   AND (NEW.acting_account_id IS NULL OR NEW.acting_account_id = OLD.acting_account_id)
                   AND (NEW.old_value IS NULL OR NEW.old_value = OLD.old_value)
                   AND (NEW.new_value IS NULL OR NEW.new_value = OLD.new_value)
                   AND (NEW.entity_id IS NOT DISTINCT FROM OLD.entity_id
                        OR (OLD.entity_type = 'WORKSPACE_ROLE' AND NEW.entity_id = 'ERASED'))
                   AND (to_jsonb(NEW) - 'entity_id' - 'actor_account_id' - 'acting_account_id' - 'old_value' - 'new_value')
                        IS NOT DISTINCT FROM (to_jsonb(OLD) - 'entity_id' - 'actor_account_id' - 'acting_account_id' - 'old_value' - 'new_value')
                THEN
                    RETURN NEW;
                END IF;
                RAISE EXCEPTION 'config_audit_event is append-only: only erasure (setting actor refs / snapshots to NULL, or erasing a membership subject) is permitted';
            END;
            $$;

CREATE FUNCTION public.conversation_receipt_words(p_workspace_id bigint, p_feedback_id uuid, p_entry_id uuid, p_turn_id uuid) RETURNS text
    LANGUAGE sql STABLE
    AS $_$
-- The one rule for the words of a conversation receipt: the text of each show_feedback call of the answer that
-- names the feedback by a well-formed id in any letter case and whose tool result is no error, in call order.
-- Only the completed answer of the claiming turn, to the developer of the feedback, counts.
SELECT NULLIF((
  SELECT string_agg(block.value->'arguments'->>'text',E'\n\n' ORDER BY block.position)
  FROM jsonb_array_elements(CASE WHEN jsonb_typeof(e.payload->'message'->'content')='array'
    THEN e.payload->'message'->'content' ELSE '[]'::jsonb END) WITH ORDINALITY AS block(value,position)
  WHERE block.value->>'type'='toolCall' AND block.value->>'name'='show_feedback'
    AND jsonb_typeof(block.value->'arguments'->'text')='string'
    AND block.value->'arguments'->>'feedbackId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    AND lower(block.value->'arguments'->>'feedbackId')=CAST(p_feedback_id AS text)
    AND EXISTS(SELECT 1 FROM mentor_entry r
      WHERE r.workspace_id=e.workspace_id AND r.turn_id=e.turn_id AND r.kind='TOOL_RESULT' AND r.redacted_at IS NULL
        AND r.payload->'message'->>'toolCallId'=block.value->>'id'
        AND r.payload->'message'->>'toolName'='show_feedback'
        AND COALESCE(r.payload->'message'->>'isError','false')<>'true')),'')
FROM mentor_entry e
JOIN mentor_conversation c ON(c.id,c.workspace_id)=(e.conversation_id,e.workspace_id)
JOIN mentor_turn t ON(t.id,t.workspace_id)=(e.turn_id,e.workspace_id)
JOIN feedback f ON(f.id,f.workspace_id)=(p_feedback_id,p_workspace_id) AND f.developer_id=c.account_id
WHERE e.id=p_entry_id AND e.workspace_id=p_workspace_id AND e.turn_id=p_turn_id
  AND e.kind='HEPH' AND e.redacted_at IS NULL AND t.status='DONE'
$_$;

CREATE FUNCTION public.enforce_consent_decision_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' OR (to_jsonb(NEW)-'account_id') IS DISTINCT FROM (to_jsonb(OLD)-'account_id') OR (NEW.account_id IS NOT NULL AND NEW.account_id IS DISTINCT FROM OLD.account_id) THEN RAISE EXCEPTION 'consent history is append-only' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.enforce_consent_notice_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
            BEGIN
                RAISE EXCEPTION 'consent_notice is immutable';
            END;
            $$;

CREATE FUNCTION public.enforce_practice_current_definition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM practice p JOIN practice_version v ON(v.id,v.practice_id,v.workspace_id)=(p.current_version_id,p.id,p.workspace_id) WHERE p.id=NEW.id AND coalesce(practice_definition_complete(v.definition),false)) THEN RAISE EXCEPTION 'current practice definition is incomplete' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;

CREATE FUNCTION public.enforce_result_children() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE p uuid;
BEGIN
  IF TG_TABLE_NAME='result' THEN
    PERFORM check_result_children(NEW.id);
  ELSE
    FOR p IN SELECT id FROM result WHERE id IN
      (CASE WHEN TG_OP<>'INSERT' THEN OLD.result_id END,
       CASE WHEN TG_OP<>'DELETE' THEN NEW.result_id END) ORDER BY id
    LOOP PERFORM check_result_children(p); END LOOP;
  END IF;
  RETURN NULL;
END $$;

CREATE FUNCTION public.enforce_review_item_scope() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN PERFORM check_review_item_scope(NEW.review_id,NEW.practice_id,NEW.item_id); RETURN NULL; END $$;

CREATE FUNCTION public.feedback_content_digest(p_id uuid) RETURNS character varying
    LANGUAGE sql STABLE STRICT
    AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object('workspaceId',f.workspace_id,'reviewId',f.review_id,'developerId',f.developer_id,'destination',f.destination,'itemId',f.item_id,'itemVersion',r.item_version,'body',f.body,
 'basis',COALESCE((SELECT jsonb_agg(jsonb_build_object('resultId',b.result_id,'problemId',b.problem_id) ORDER BY b.result_id::text,b.problem_id::text NULLS FIRST) FROM feedback_basis b WHERE b.feedback_id=f.id),'[]'::jsonb),
 'copies',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',v.id,'kind',CASE WHEN v.kind IN('LINE_NOTE','FILE_NOTE') THEN 'NOTE' ELSE v.kind END,'position',v.position,'body',CASE WHEN v.kind='CHAT_TURN' THEN NULL ELSE v.body END,'path',v.path,'startLine',v.start_line,'endLine',v.end_line,'side',v.side) ORDER BY v.position) FROM feedback_copy v WHERE v.feedback_id=f.id),'[]'::jsonb))::text,'UTF8')),'hex')::varchar(64)
 FROM feedback f JOIN review r ON r.id=f.review_id AND r.workspace_id=f.workspace_id WHERE f.id=p_id
$$;

CREATE FUNCTION public.feedback_package_digest(p_digests text[]) RETURNS character varying
    LANGUAGE plpgsql IMMUTABLE STRICT
    AS $_$
BEGIN
 IF array_ndims(p_digests)>1 OR EXISTS(SELECT 1 FROM unnest(p_digests) d WHERE d IS NULL OR d !~ '^[0-9a-f]{64}$') THEN
  RAISE EXCEPTION 'package pieces must be an ordered list of SHA-256 digests' USING ERRCODE='23514';
 END IF;
 RETURN encode(sha256(convert_to(to_jsonb(p_digests)::text,'UTF8')),'hex')::varchar(64);
END $_$;

CREATE FUNCTION public.guard_feedback_copy_fence() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE shown_text text;
BEGIN
 IF OLD.write_started_at IS NOT NULL THEN
  IF NEW.write_started_at IS DISTINCT FROM OLD.write_started_at OR
     ROW(NEW.id,NEW.workspace_id,NEW.feedback_id,NEW.position,NEW.write_key,NEW.write_marker,NEW.path,NEW.start_line,NEW.end_line,NEW.side)
     IS DISTINCT FROM ROW(OLD.id,OLD.workspace_id,OLD.feedback_id,OLD.position,OLD.write_key,OLD.write_marker,OLD.path,OLD.start_line,OLD.end_line,OLD.side) THEN
   RAISE EXCEPTION 'physical write fence is immutable' USING ERRCODE='55000';
  END IF;
  IF NEW.mentor_turn_id IS DISTINCT FROM OLD.mentor_turn_id AND NOT
     (NEW.mentor_turn_id IS NULL AND NOT EXISTS(SELECT 1 FROM mentor_turn WHERE id=OLD.mentor_turn_id AND workspace_id=OLD.workspace_id)) THEN
   RAISE EXCEPTION 'conversation turn binding is immutable' USING ERRCODE='55000';
  END IF;
  IF OLD.mentor_entry_id IS NOT NULL AND NEW.mentor_entry_id IS DISTINCT FROM OLD.mentor_entry_id AND NOT
     (NEW.mentor_entry_id IS NULL AND NOT EXISTS(SELECT 1 FROM mentor_entry WHERE id=OLD.mentor_entry_id AND workspace_id=OLD.workspace_id)) THEN
   RAISE EXCEPTION 'conversation answer binding is immutable' USING ERRCODE='55000';
  END IF;
  IF OLD.mentor_entry_id IS NULL AND NEW.mentor_entry_id IS NOT NULL AND OLD.posted_at IS NOT NULL THEN
   RAISE EXCEPTION 'conversation answer binding is immutable' USING ERRCODE='55000';
  END IF;
  IF OLD.mentor_entry_id IS NULL AND NEW.mentor_entry_id IS NOT NULL AND NOT EXISTS(
     SELECT 1 FROM mentor_entry e JOIN mentor_conversation c ON(c.id,c.workspace_id)=(e.conversation_id,e.workspace_id)
     JOIN feedback f ON(f.id,f.workspace_id)=(NEW.feedback_id,NEW.workspace_id) AND f.developer_id=c.account_id
     WHERE e.id=NEW.mentor_entry_id AND e.workspace_id=NEW.workspace_id AND e.kind='HEPH' AND e.turn_id=NEW.mentor_turn_id) THEN
   RAISE EXCEPTION 'conversation answer must be the claiming turn answer to its developer' USING ERRCODE='23514';
  END IF;
  IF NEW.body IS DISTINCT FROM OLD.body THEN
   IF NOT(OLD.kind='CHAT_TURN' AND OLD.body IS NULL AND NEW.body IS NOT NULL AND
          OLD.posted_at IS NULL AND NEW.posted_at IS NOT NULL AND NEW.posted_at>=OLD.write_started_at AND
          OLD.state IN('WRITE_STARTED','FAILED') AND NEW.state='POSTED' AND
          NEW.mentor_entry_id IS NOT NULL AND NEW.mentor_turn_id IS NOT NULL) THEN
    RAISE EXCEPTION 'physical copy words are immutable' USING ERRCODE='55000';
   END IF;
   shown_text := conversation_receipt_words(NEW.workspace_id,NEW.feedback_id,NEW.mentor_entry_id,NEW.mentor_turn_id);
   IF shown_text IS NULL OR shown_text ~ '^[[:space:]]*$' OR NEW.body IS DISTINCT FROM shown_text THEN
    RAISE EXCEPTION 'conversation receipt must contain the words the completed answer showed' USING ERRCODE='23514';
   END IF;
  END IF;
  IF OLD.kind='CHAT_TURN' AND OLD.posted_at IS NULL AND NEW.posted_at IS NOT NULL AND NEW.body IS NULL THEN
   RAISE EXCEPTION 'conversation receipt requires actual words' USING ERRCODE='23514';
  END IF;
 ELSIF NEW.write_started_at IS NOT NULL AND NEW.kind='CHAT_TURN' THEN
  IF NEW.body IS NOT NULL OR NEW.state<>'WRITE_STARTED' OR NOT EXISTS(
     SELECT 1 FROM mentor_turn t JOIN mentor_conversation c ON(c.id,c.workspace_id)=(t.conversation_id,t.workspace_id)
     JOIN feedback f ON(f.id,f.workspace_id)=(NEW.feedback_id,NEW.workspace_id) AND f.developer_id=c.account_id
     WHERE t.id=NEW.mentor_turn_id AND t.workspace_id=NEW.workspace_id AND t.status='RUNNING' AND f.destination='CONVERSATION') THEN
   RAISE EXCEPTION 'conversation fence requires a running Heph turn of its developer' USING ERRCODE='23514';
  END IF;
 END IF;
 IF OLD.kind<>NEW.kind AND NOT(OLD.kind='LINE_NOTE' AND NEW.kind='FILE_NOTE') THEN RAISE EXCEPTION 'copy form cannot change except acknowledged fallback' USING ERRCODE='55000'; END IF;
 IF OLD.external_ref IS NOT NULL AND NEW.external_ref IS DISTINCT FROM OLD.external_ref OR OLD.external_url IS NOT NULL AND NEW.external_url IS DISTINCT FROM OLD.external_url OR OLD.posted_at IS NOT NULL AND NEW.posted_at IS DISTINCT FROM OLD.posted_at THEN RAISE EXCEPTION 'physical write receipt is immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $_$;

CREATE FUNCTION public.guard_feedback_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_TABLE_NAME='feedback_decision' THEN
  IF to_jsonb(NEW)-'actor_account_id' IS NOT DISTINCT FROM to_jsonb(OLD)-'actor_account_id' AND (NEW.actor_account_id IS NULL OR NEW.actor_account_id IS NOT DISTINCT FROM OLD.actor_account_id) THEN RETURN NEW; END IF;
  IF NEW.actor_account_id IS NULL
     AND to_jsonb(NEW)-'actor_account_id'-'reason'-'note' IS NOT DISTINCT FROM to_jsonb(OLD)-'actor_account_id'-'reason'-'note'
     AND (NEW.reason IS NOT DISTINCT FROM OLD.reason OR (OLD.reason IS NOT NULL AND NEW.reason='Erased administrator text'))
     AND (NEW.note IS NULL OR NEW.note IS NOT DISTINCT FROM OLD.note) THEN RETURN NEW; END IF;
 END IF;
 RAISE EXCEPTION 'feedback history is append-only' USING ERRCODE='55000';
END $$;

CREATE FUNCTION public.guard_feedback_send_destination() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM feedback f WHERE f.id=NEW.feedback_id AND f.workspace_id=NEW.workspace_id AND f.destination='ON_WORK') THEN RAISE EXCEPTION 'only on-work feedback has a provider send' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.guard_item_event_facts() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF ROW(NEW.id,NEW.workspace_id,NEW.item_id,NEW.name,NEW.occurred_at,NEW.found_by,NEW.pending_fence)
    IS DISTINCT FROM ROW(OLD.id,OLD.workspace_id,OLD.item_id,OLD.name,OLD.occurred_at,OLD.found_by,OLD.pending_fence)
    OR (OLD.item_version IS NOT NULL AND NEW.item_version IS DISTINCT FROM OLD.item_version)
    OR (OLD.item_version IS NULL AND NEW.item_version IS NOT NULL AND OLD.state NOT IN ('RECORDED','WAITING')) THEN
  RAISE EXCEPTION 'event facts are immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.guard_item_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='UPDATE' AND (to_jsonb(NEW)-'removed_at') IS DISTINCT FROM (to_jsonb(OLD)-'removed_at') THEN
  RAISE EXCEPTION 'item identity is immutable' USING ERRCODE='55000'; END IF;
 IF TG_OP='INSERT' AND NEW.parent_id IS NOT NULL AND NOT EXISTS
   (SELECT 1 FROM item p WHERE p.id=NEW.parent_id AND p.workspace_id=NEW.workspace_id) THEN
  RAISE EXCEPTION 'item parent must already exist in its workspace' USING ERRCODE='23503'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.guard_practice_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF ROW(NEW.id,NEW.workspace_id,NEW.slug,NEW.kind) IS DISTINCT FROM ROW(OLD.id,OLD.workspace_id,OLD.slug,OLD.kind) THEN RAISE EXCEPTION 'practice identity is immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.guard_practice_version() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF (to_jsonb(NEW)-'created_by_account_id'-'name'-'review_digest') IS DISTINCT FROM (to_jsonb(OLD)-'created_by_account_id'-'name'-'review_digest') OR (NEW.created_by_account_id IS NOT NULL AND NEW.created_by_account_id IS DISTINCT FROM OLD.created_by_account_id) THEN RAISE EXCEPTION 'practice versions are immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.guard_result_facts() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF (to_jsonb(NEW)-'ended_at'-'end_reason'-'severity'-'developer_id') IS DISTINCT FROM
    (to_jsonb(OLD)-'ended_at'-'end_reason'-'severity'-'developer_id') THEN
  RAISE EXCEPTION 'result facts are immutable' USING ERRCODE='55000'; END IF;
 IF NEW.developer_id IS DISTINCT FROM OLD.developer_id AND NOT EXISTS(
   SELECT 1 FROM account old_account,account target WHERE old_account.id=OLD.developer_id
    AND old_account.status='UNCLAIMED' AND target.id=NEW.developer_id AND target.status IN ('ACTIVE','SUSPENDED')) THEN
  RAISE EXCEPTION 'result developer can move only from an unclaimed account to its claimed owner' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.guard_result_insert() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE v review%ROWTYPE;
BEGIN
 IF NEW.evidence_version<>1 THEN RAISE EXCEPTION 'new results require evidence version 1' USING ERRCODE='23514'; END IF;
 SELECT * INTO v FROM review WHERE id=NEW.review_id AND workspace_id=NEW.workspace_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'result review is outside workspace' USING ERRCODE='23503'; END IF;
 IF NOT EXISTS(SELECT 1 FROM review_practice p WHERE p.review_id=NEW.review_id
      AND p.practice_id=NEW.practice_id AND p.workspace_id=NEW.workspace_id
      AND p.practice_version_id=NEW.practice_version_id) THEN
  RAISE EXCEPTION 'result does not match pinned practice version' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM review_item a WHERE a.review_id=NEW.review_id AND a.practice_id=NEW.practice_id
  AND a.item_id=NEW.item_id AND a.workspace_id=NEW.workspace_id AND a.item_version=NEW.item_version) THEN
  RAISE EXCEPTION 'result has no frozen review assignment' USING ERRCODE='23514'; END IF;
 IF v.kind='ITEM' AND (NEW.item_id<>v.item_id OR NEW.item_version IS DISTINCT FROM v.item_version) THEN
  RAISE EXCEPTION 'item result differs from review scope/version' USING ERRCODE='23514'; END IF;
 IF v.kind='SCAN' AND NOT EXISTS(SELECT 1 FROM item i WHERE i.id=NEW.item_id
   AND i.workspace_id=NEW.workspace_id AND i.parent_id=v.item_id AND EXISTS(SELECT 1 FROM review_practice rp JOIN practice_version pv ON pv.id=rp.practice_version_id AND pv.practice_id=rp.practice_id AND pv.workspace_id=rp.workspace_id WHERE rp.review_id=NEW.review_id AND rp.practice_id=NEW.practice_id AND rp.workspace_id=NEW.workspace_id AND i.kind=pv.definition->'scan'->>'itemKind')) THEN
  RAISE EXCEPTION 'scan result is outside pinned item scope' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.guard_review_item_write() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='INSERT' AND NEW.item_version IS NULL THEN
  RAISE EXCEPTION 'new assignment requires a frozen version' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND ROW(NEW.workspace_id,NEW.review_id,NEW.practice_id,NEW.item_id,NEW.item_version)
   IS DISTINCT FROM ROW(OLD.workspace_id,OLD.review_id,OLD.practice_id,OLD.item_id,OLD.item_version) THEN
  RAISE EXCEPTION 'assignment identity/version is immutable' USING ERRCODE='55000'; END IF;
 IF NEW.state='REUSED' AND (TG_OP='INSERT' OR NEW.state IS DISTINCT FROM OLD.state) AND EXISTS(
   SELECT 1 FROM result r WHERE r.id=NEW.result_id AND r.evidence_version=0) THEN
  RAISE EXCEPTION 'new assignments cannot reuse released evidence' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.practice_definition_complete(d jsonb) RETURNS boolean
    LANGUAGE plpgsql IMMUTABLE STRICT
    AS $_$
DECLARE
  e jsonb;
  read_policy jsonb;
  k text;
  seen text[];
  clause_count integer;
  policy text;
BEGIN
  IF jsonb_typeof(d)<>'object' OR NOT d ?& ARRAY[
    'name','reviewOn','appliesWhen','about','reads','reviewedBy','criteria',
    'script','scan','lineNotes','guidance'] THEN RETURN false; END IF;
  IF d - ARRAY['name','reviewOn','appliesWhen','about','reads','reviewedBy','criteria',
    'script','scan','lineNotes','guidance'] <> '{}'::jsonb THEN RETURN false; END IF;
  IF jsonb_typeof(d->'name')<>'string' OR nullif(btrim(d->>'name'),'') IS NULL
    OR length(d->>'name')>128 OR jsonb_typeof(d->'criteria')<>'string'
    OR nullif(btrim(d->>'criteria'),'') IS NULL THEN RETURN false; END IF;
  IF d->'about'<>'null'::jsonb AND
    (jsonb_typeof(d->'about')<>'string' OR d->>'about' NOT IN ('AUTHOR','REVIEWER','ASSIGNEE','MERGER'))
    THEN RETURN false; END IF;
  IF jsonb_typeof(d->'reviewOn')<>'object'
    OR NOT (d->'reviewOn') ?& ARRAY['events','state']
    OR (d->'reviewOn')-ARRAY['events','state']<>'{}'::jsonb
    OR jsonb_typeof(d->'reviewOn'->'events')<>'array'
    OR jsonb_typeof(d->'reviewOn'->'state')<>'object' THEN RETURN false; END IF;
  FOR e IN SELECT value FROM jsonb_array_elements(d->'reviewOn'->'events') LOOP
    IF jsonb_typeof(e)<>'string' OR nullif(btrim(e#>>'{}'),'') IS NULL THEN RETURN false; END IF;
  END LOOP;
  FOR k,e IN SELECT key,value FROM jsonb_each(d->'reviewOn'->'state') LOOP
    IF nullif(btrim(k),'') IS NULL OR jsonb_typeof(e)<>'array' THEN RETURN false; END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(e) x WHERE jsonb_typeof(x.value)<>'string')
      THEN RETURN false; END IF;
  END LOOP;
  IF d->'appliesWhen'<>'null'::jsonb THEN
    IF jsonb_typeof(d->'appliesWhen')<>'object'
      OR NOT (d->'appliesWhen') ?& ARRAY['anyOf','otherwise']
      OR (d->'appliesWhen')-ARRAY['anyOf','otherwise']<>'{}'::jsonb
      OR jsonb_typeof(d->'appliesWhen'->'anyOf')<>'array'
      OR jsonb_array_length(d->'appliesWhen'->'anyOf')=0
      OR jsonb_typeof(d->'appliesWhen'->'otherwise')<>'string' THEN RETURN false; END IF;
    FOR e IN SELECT value FROM jsonb_array_elements(d->'appliesWhen'->'anyOf') LOOP
      IF jsonb_typeof(e)<>'object' OR e-ARRAY['changedPath','diffContains','hasItems']<>'{}'::jsonb
        THEN RETURN false; END IF;
      SELECT count(*) INTO clause_count FROM jsonb_object_keys(e);
      IF clause_count<>1 THEN RETURN false; END IF;
      IF e ? 'hasItems' THEN
        IF jsonb_typeof(e->'hasItems')<>'string' THEN RETURN false; END IF;
      ELSE
        FOR k IN SELECT jsonb_object_keys(e) LOOP
          IF jsonb_typeof(e->k)<>'array' OR jsonb_array_length(e->k)=0
            OR EXISTS (SELECT 1 FROM jsonb_array_elements(e->k) x
              WHERE jsonb_typeof(x.value)<>'string' OR nullif(btrim(x.value#>>'{}'),'') IS NULL)
            THEN RETURN false; END IF;
        END LOOP;
      END IF;
    END LOOP;
  END IF;
  FOR read_policy IN
    SELECT d->'reads' UNION ALL SELECT d->'scan'->'reads' WHERE d->'scan'<>'null'::jsonb
  LOOP
  IF jsonb_typeof(read_policy)<>'object' OR NOT (read_policy) ?& ARRAY['contract','sources']
    OR (read_policy)-ARRAY['contract','sources']<>'{}'::jsonb
    OR jsonb_typeof(read_policy->'contract')<>'string'
    OR jsonb_typeof(read_policy->'sources')<>'array' THEN RETURN false; END IF;
  seen:=ARRAY[]::text[];
  FOR e IN SELECT value FROM jsonb_array_elements(read_policy->'sources') LOOP
    IF jsonb_typeof(e)<>'object' OR NOT e ?& ARRAY['source','need']
      OR e-ARRAY['source','need']<>'{}'::jsonb OR jsonb_typeof(e->'source')<>'string'
      OR jsonb_typeof(e->'need')<>'string' OR e->>'need' NOT IN ('REQUIRED','COMPLETE','OPTIONAL')
      OR nullif(btrim(e->>'source'),'') IS NULL OR e->>'source'=ANY(seen)
      THEN RETURN false; END IF;
    seen:=array_append(seen,e->>'source');
  END LOOP;
  END LOOP;
  IF jsonb_typeof(d->'reviewedBy')<>'object'
    OR NOT (d->'reviewedBy') ?& ARRAY['by','humanReason','limits']
    OR (d->'reviewedBy')-ARRAY['by','humanReason','limits']<>'{}'::jsonb
    OR jsonb_typeof(d->'reviewedBy'->'by')<>'string'
    OR jsonb_typeof(d->'reviewedBy'->'limits')<>'array' THEN RETURN false; END IF;
  policy:=d->'reviewedBy'->>'by';
  IF policy NOT IN ('HEPHAESTUS','PEOPLE','NOBODY') THEN RETURN false; END IF;
  IF (policy='PEOPLE') <> (d->'reviewedBy'->'humanReason'<>'null'::jsonb) THEN RETURN false; END IF;
  seen:=ARRAY[]::text[];
  FOR e IN SELECT value FROM jsonb_array_elements(d->'reviewedBy'->'limits') LOOP
    IF jsonb_typeof(e)<>'object' OR NOT e ?& ARRAY['code','description']
      OR e-ARRAY['code','description']<>'{}'::jsonb
      OR jsonb_typeof(e->'code')<>'string' OR jsonb_typeof(e->'description')<>'string'
      OR nullif(btrim(e->>'code'),'') IS NULL OR e->>'code'=ANY(seen) THEN RETURN false; END IF;
    seen:=array_append(seen,e->>'code');
  END LOOP;
  IF policy='PEOPLE' THEN
    e:=d->'reviewedBy'->'humanReason';
    IF jsonb_typeof(e)<>'object' OR NOT e ?& ARRAY['code','description']
      OR e-ARRAY['code','description']<>'{}'::jsonb
      OR jsonb_typeof(e->'code')<>'string' OR jsonb_typeof(e->'description')<>'string' THEN RETURN false; END IF;
  END IF;
  IF policy='HEPHAESTUS' AND NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(d->'reads'->'sources') x
      WHERE x.value->>'need' IN ('REQUIRED','COMPLETE')) THEN RETURN false; END IF;
  IF d->'script'<>'null'::jsonb AND (jsonb_typeof(d->'script')<>'string'
    OR length(d->>'script')>100000 OR nullif(btrim(d->>'script'),'') IS NULL) THEN RETURN false; END IF;
  IF jsonb_typeof(d->'lineNotes')<>'boolean' THEN RETURN false; END IF;
  IF d->'scan'<>'null'::jsonb THEN
    IF policy<>'HEPHAESTUS' OR jsonb_typeof(d->'scan')<>'object'
      OR NOT (d->'scan') ?& ARRAY['itemKind','include','exclude','context','reads']
      OR (d->'scan')-ARRAY['itemKind','include','exclude','context','reads']<>'{}'::jsonb
      OR jsonb_typeof(d->'scan'->'itemKind')<>'string' OR d->'scan'->>'itemKind' !~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$' THEN RETURN false; END IF;
    FOREACH k IN ARRAY ARRAY['include','exclude','context'] LOOP
      IF jsonb_typeof(d->'scan'->k)<>'array' OR jsonb_array_length(d->'scan'->k)>64
        THEN RETURN false; END IF;
      FOR e IN SELECT value FROM jsonb_array_elements(d->'scan'->k) LOOP
        IF jsonb_typeof(e)<>'string' OR nullif(btrim(e#>>'{}'),'') IS NULL
          OR length(e#>>'{}')>4096 THEN RETURN false; END IF;
      END LOOP;
    END LOOP;
    IF jsonb_array_length(d->'scan'->'include')=0 THEN RETURN false; END IF;
  END IF;
  IF jsonb_typeof(d->'guidance')<>'object'
    OR NOT (d->'guidance') ?& ARRAY['whyItMatters','whatGoodLooksLike','goingWell','visual','guide']
    OR (d->'guidance')-ARRAY['whyItMatters','whatGoodLooksLike','goingWell','visual','guide']<>'{}'::jsonb
    THEN RETURN false; END IF;
  FOREACH k IN ARRAY ARRAY['whyItMatters','whatGoodLooksLike','goingWell'] LOOP
    IF d->'guidance'->k<>'null'::jsonb AND jsonb_typeof(d->'guidance'->k)<>'string' THEN RETURN false; END IF;
  END LOOP;
  e:=d->'guidance'->'visual';
  IF e<>'null'::jsonb AND (jsonb_typeof(e)<>'object' OR NOT e ?& ARRAY['svg','alt']
    OR e-ARRAY['svg','alt']<>'{}'::jsonb OR jsonb_typeof(e->'svg')<>'string'
    OR jsonb_typeof(e->'alt')<>'string') THEN RETURN false; END IF;
  e:=d->'guidance'->'guide';
  IF e<>'null'::jsonb THEN
    IF jsonb_typeof(e)<>'object' OR NOT e ?& ARRAY['markdown','figures']
      OR e-ARRAY['markdown','figures']<>'{}'::jsonb OR jsonb_typeof(e->'markdown')<>'string'
      OR jsonb_typeof(e->'figures')<>'object' THEN RETURN false; END IF;
    IF EXISTS (SELECT 1 FROM jsonb_each(e->'figures') x WHERE jsonb_typeof(x.value)<>'string')
      THEN RETURN false; END IF;
  END IF;
  RETURN true;
EXCEPTION WHEN invalid_parameter_value OR invalid_text_representation OR numeric_value_out_of_range
  THEN RETURN false;
END
$_$;

CREATE FUNCTION public.practice_review_digest(d jsonb) RETURNS character varying
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$
 SELECT ('v6:' || encode(sha256(convert_to(jsonb_build_object(
  'knownFields',jsonb_build_object('appliesWhen',d ? 'appliesWhen','about',d ? 'about',
    'reads',d ? 'reads','reviewedBy',d ? 'reviewedBy','criteria',d ? 'criteria','script',d ? 'script','scan',d ? 'scan'),
  'appliesWhen',d->'appliesWhen','about',d->'about','reads',d->'reads',
  'reviewedBy',d->'reviewedBy','criteria',d->'criteria','script',d->'script','scan',d->'scan'
 )::text,'UTF8')),'hex'))::varchar(96)
$$;

CREATE FUNCTION public.reserve_workspace_slug() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.slug IS DISTINCT FROM OLD.slug THEN
        INSERT INTO workspace_slug_reservation(slug) VALUES (NEW.slug);
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.review_model_digest(models jsonb) RETURNS character varying
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$
 SELECT encode(sha256(convert_to(COALESCE(jsonb_agg(value ORDER BY value->>'slot'),'[]'::jsonb)::text,'UTF8')),'hex')::varchar(64)
 FROM jsonb_array_elements(models)
$$;

CREATE FUNCTION public.touch_problem_parent() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE p uuid;
BEGIN
  FOR p IN SELECT id FROM result WHERE id IN
    (CASE WHEN TG_OP<>'INSERT' THEN OLD.result_id END,
     CASE WHEN TG_OP<>'DELETE' THEN NEW.result_id END) ORDER BY id
  LOOP
    UPDATE result SET severity=severity WHERE id=p;
  END LOOP;
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE FUNCTION public.workspace_slug_assignable(label text) RETURNS boolean
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $_$
    SELECT length(label) BETWEEN 3 AND 51
        AND label COLLATE "C" ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        AND position('--' IN label) = 0
        AND label !~ '^pr[0-9]+$'
        AND label NOT IN (
            'www', 'api', 'docs', 'admin', 'auth', 'login', 'mail', 'status',
            'staging', 'preview', 'info', 'marketing', 'sales', 'support', 'abuse', 'noc',
            'security', 'postmaster', 'hostmaster', 'usenet', 'news', 'webmaster', 'uucp', 'ftp',
            'w', 'settings', 'integrations', 'consent', 'legal', 'privacy', 'terms', 'unsubscribe',
            'landing', 'oauth', 'oauth2', 'webhooks', 'actuator', 'livez', 'readyz', 'error',
            'swagger-ui', 'v3', 'identity-providers', 'workspaces', 'workers', 'mentor', 'conversation', 'feedback', 'practices',
            'activity', 'about', 'imprint', 'audit', 'catalog', 'login-providers', 'models', 'person-data',
            'surveys', 'usage', 'users', 'onboarding', 'practice-profile', 'practices-across-the-workspace', 'workspace-activity', 'new',
            'github', 'gitlab', 'members', 'teams', 'outline', 'scm', 'slack', 'releases',
            'review', 'reviews', 'user', 'observations', 'runs', 'work', 'practice-groups', 'assets',
            'static', 'cdn', 'traefik', 'grafana', 'prometheus', 'nats', 'postgres', 'registry',
            'agents', 'config-audit', 'connections', 'contributors', 'internal', 'llm', 'practice-catalog', 'product-feedback',
            'sync', 'team', 'user-view', 'notifications', 'providers'
        );
$_$;

CREATE TABLE public.account (
    id bigint NOT NULL,
    display_name character varying(255) NOT NULL,
    primary_email public.citext,
    primary_email_verified_at timestamp with time zone,
    app_role character varying(16) DEFAULT 'USER'::character varying NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    public_activity_visible boolean DEFAULT false NOT NULL,
    ai_choice character varying(24),
    ai_choice_at timestamp with time zone,
    feedback_delivery_enabled boolean DEFAULT true NOT NULL,
    passkey_protection_enabled boolean DEFAULT false NOT NULL,
    passkey_recovery_required boolean DEFAULT false NOT NULL,
    passkey_user_handle character varying(64),
    time_zone character varying(64),
    CONSTRAINT ck_account_ai_choice CHECK (((ai_choice IS NULL) OR ((ai_choice)::text = ANY ((ARRAY['NO_AI'::character varying, 'IN_HOUSE_ONLY'::character varying, 'CLOUD'::character varying])::text[])))),
    CONSTRAINT ck_account_ai_choice_time CHECK (((ai_choice IS NULL) = (ai_choice_at IS NULL))),
    CONSTRAINT ck_account_app_role CHECK (((app_role)::text = ANY (ARRAY[('USER'::character varying)::text, ('APP_ADMIN'::character varying)::text]))),
    CONSTRAINT ck_account_status CHECK (((status)::text = ANY ((ARRAY['UNCLAIMED'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'DELETING'::character varying, 'DELETED'::character varying])::text[]))),
    CONSTRAINT ck_account_unclaimed CHECK ((((status)::text <> 'UNCLAIMED'::text) OR ((primary_email IS NULL) AND (primary_email_verified_at IS NULL) AND ((app_role)::text = 'USER'::text))))
);

CREATE TABLE public.account_export (
    id bigint NOT NULL,
    account_id bigint NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    expires_at timestamp with time zone,
    failure_reason character varying(128),
    payload bytea,
    CONSTRAINT ck_account_export_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text, ('READY'::character varying)::text, ('FAILED'::character varying)::text, ('EXPIRED'::character varying)::text])))
);

ALTER TABLE public.account_export ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.account_export_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.account_feature (
    account_id bigint NOT NULL,
    flag character varying(64) NOT NULL,
    enabled_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE public.account ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.account_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.account_public_activity_hidden_by_default (
    account_id bigint NOT NULL
);

CREATE TABLE public.activity_automation (
    user_id bigint NOT NULL,
    workspace_id bigint NOT NULL
);

CREATE TABLE public.activity_event (
    id uuid NOT NULL,
    event_key character varying(255) NOT NULL,
    event_type character varying(64) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    actor_id bigint,
    workspace_id bigint NOT NULL,
    repository_id bigint,
    target_type character varying(32),
    target_id bigint,
    ingested_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.auth_event (
    id bigint NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    account_id bigint,
    acting_account_id bigint,
    event_type character varying(48) NOT NULL,
    result character varying(16) NOT NULL,
    failure_reason character varying(64),
    provider_id bigint,
    workspace_id bigint,
    identity_id bigint,
    ip_inet inet,
    user_agent character varying(512),
    details text,
    elevated_via_instance_admin boolean DEFAULT false NOT NULL,
    viewed_actor_id bigint,
    viewed_account_id bigint,
    CONSTRAINT ck_auth_event_event_type CHECK (((event_type)::text = ANY ((ARRAY['USER_VIEW'::character varying, 'LOGIN'::character varying, 'PASSKEY_CHANGED'::character varying, 'PASSKEY_VERIFIED'::character varying, 'LOGIN_FAILED'::character varying, 'LOGOUT'::character varying, 'TOKEN_REFRESH'::character varying, 'JWT_REVOKED'::character varying, 'IDENTITY_LINKED'::character varying, 'IDENTITY_UNLINKED'::character varying, 'IMPERSONATION_BEGIN'::character varying, 'IMPERSONATION_END'::character varying, 'ACCOUNT_DELETED'::character varying, 'EXPORT_REQUESTED'::character varying, 'PERSON_DATA_PREVIEWED'::character varying, 'PERSON_DATA_EXPORTED'::character varying, 'PERSON_DATA_ERASURE_REQUESTED'::character varying, 'APP_ROLE_CHANGED'::character varying, 'RESEARCH_CONSENT_REVOKED'::character varying, 'WORKSPACE_ELEVATION'::character varying, 'INSTALLATION_EVENT_REPLAYED'::character varying, 'LLM_CONNECTION_CREATED'::character varying, 'LLM_CONNECTION_UPDATED'::character varying, 'LLM_CONNECTION_DELETED'::character varying, 'LLM_MODEL_CREATED'::character varying, 'LLM_MODEL_UPDATED'::character varying, 'LLM_MODEL_DELETED'::character varying, 'LLM_MODEL_PRICE_CHANGED'::character varying, 'LLM_MODEL_SHARING_CHANGED'::character varying, 'LLM_SETTINGS_CHANGED'::character varying, 'LOGIN_PROVIDER_CREATED'::character varying, 'LOGIN_PROVIDER_UPDATED'::character varying, 'LOGIN_PROVIDER_DELETED'::character varying, 'SILENT_MODE_CHANGED'::character varying, 'PUBLIC_ACTIVITY_CHANGED'::character varying, 'RESEARCH_DATASET_RELEASED'::character varying, 'RESEARCH_REMOVAL_RECORDED'::character varying])::text[]))),
    CONSTRAINT ck_auth_event_result CHECK (((result)::text = ANY (ARRAY[('SUCCESS'::character varying)::text, ('FAILURE'::character varying)::text])))
)
PARTITION BY RANGE (occurred_at);

CREATE SEQUENCE public.auth_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.auth_event_id_seq OWNED BY public.auth_event.id;

CREATE TABLE public.auth_rate_limit_bucket (
    id character varying(255) NOT NULL,
    state bytea,
    expires_at bigint
);

CREATE TABLE public.catalog_entry (
    entry_type character varying(16) NOT NULL,
    slug character varying(64) NOT NULL,
    kind character varying(64),
    definition jsonb,
    base jsonb,
    "position" integer,
    excluded_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_catalog_entry_base CHECK (((base IS NULL) OR (jsonb_typeof(base) = 'object'::text))),
    CONSTRAINT ck_catalog_entry_definition CHECK (((definition IS NULL) OR (jsonb_typeof(definition) = 'object'::text))),
    CONSTRAINT ck_catalog_entry_kind CHECK ((((((entry_type)::text = 'GROUP'::text) OR (definition IS NULL)) = (kind IS NULL)) AND ((kind IS NULL) OR ((kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text)))),
    CONSTRAINT ck_catalog_entry_position CHECK ((("position" IS NULL) OR ("position" >= 0))),
    CONSTRAINT ck_catalog_entry_type CHECK (((entry_type)::text = ANY ((ARRAY['PRACTICE'::character varying, 'GROUP'::character varying])::text[])))
);

CREATE TABLE public.client_session (
    id uuid NOT NULL,
    account_id bigint NOT NULL,
    auth_time timestamp(6) with time zone NOT NULL,
    client_id character varying(64) NOT NULL,
    client_kind character varying(32) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    revoked_reason character varying(32),
    session_expires_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE public.client_sign_in_handoff (
    code_hash character varying(64) NOT NULL,
    account_id bigint NOT NULL,
    auth_time timestamp(6) with time zone NOT NULL,
    client_id character varying(64) NOT NULL,
    client_kind character varying(32) NOT NULL,
    code_challenge character varying(64) NOT NULL,
    consumed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    redirect_uri character varying(255) NOT NULL,
    session_expires_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE public.commit_contributor (
    id bigint NOT NULL,
    commit_id bigint NOT NULL,
    user_id bigint,
    role character varying(32) NOT NULL,
    name character varying(255),
    email character varying(255) NOT NULL,
    ordinal integer NOT NULL
);

ALTER TABLE public.commit_contributor ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.commit_contributor_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.commit_file_change (
    id bigint NOT NULL,
    filename character varying(1024) NOT NULL,
    change_type character varying(32) NOT NULL,
    additions integer NOT NULL,
    deletions integer NOT NULL,
    changes integer NOT NULL,
    previous_filename character varying(1024),
    commit_id bigint NOT NULL
);

ALTER TABLE public.commit_file_change ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.commit_file_change_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.commit_pull_request (
    commit_id bigint NOT NULL,
    pull_request_id bigint NOT NULL
);

CREATE TABLE public.config_audit_event (
    id bigint NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    workspace_id bigint,
    actor_kind character varying(16) NOT NULL,
    actor_account_id bigint,
    acting_account_id bigint,
    entity_type character varying(48) NOT NULL,
    entity_id character varying(64) NOT NULL,
    action character varying(16) NOT NULL,
    changed_keys text[] NOT NULL,
    old_value text,
    new_value text,
    elevated_via_instance_admin boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_config_audit_event_action CHECK (((action)::text = ANY (ARRAY[('CREATED'::character varying)::text, ('UPDATED'::character varying)::text, ('DELETED'::character varying)::text]))),
    CONSTRAINT ck_config_audit_event_actor_kind CHECK (((actor_kind)::text = ANY (ARRAY[('USER'::character varying)::text, ('SYSTEM'::character varying)::text, ('IMPERSONATED'::character varying)::text]))),
    CONSTRAINT ck_config_audit_event_entity_type CHECK (((entity_type)::text = ANY ((ARRAY['PRACTICE_REVIEW_SETTINGS'::character varying, 'AGENT_BINDING'::character varying, 'AGENT_CONFIG'::character varying, 'AI_CONFIG_BINDING'::character varying, 'WORKSPACE_ROLE'::character varying, 'ACTIVITY_AUTOMATION'::character varying, 'WORKSPACE_FEATURES'::character varying, 'WORKSPACE_STATUS'::character varying, 'WORKSPACE_TOKEN'::character varying, 'WORKSPACE_VISIBILITY'::character varying, 'PRACTICE_ACTIVE'::character varying, 'PRACTICE_USAGE'::character varying, 'PRACTICE_DEFINITION'::character varying, 'PRACTICE_GROUP'::character varying, 'CATALOG_PRACTICE'::character varying, 'CATALOG_GROUP'::character varying, 'WORKSPACE_INSTANCE_LLM_BUDGET'::character varying, 'WORKSPACE_OWN_PROVIDER_LLM_BUDGET'::character varying, 'WORKSPACE_LLM_BUDGET'::character varying, 'WORKSPACE_BYO_LLM_BUDGET'::character varying, 'REVIEW_BATCH'::character varying, 'REVIEW_SCHEDULE'::character varying, 'WORKSPACE_LLM_CONNECTION'::character varying, 'WORKSPACE_LLM_MODEL'::character varying, 'FEEDBACK_DELIVERY_RECHECK'::character varying])::text[]))),
    CONSTRAINT ck_config_audit_event_scope CHECK (((((entity_type)::text = ANY ((ARRAY['CATALOG_PRACTICE'::character varying, 'CATALOG_GROUP'::character varying])::text[])) AND (workspace_id IS NULL)) OR (((entity_type)::text <> 'CATALOG_PRACTICE'::text) AND (workspace_id IS NOT NULL))))
);

CREATE SEQUENCE public.config_audit_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.config_audit_event_id_seq OWNED BY public.config_audit_event.id;

CREATE TABLE public.connection (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    kind character varying(48) NOT NULL,
    instance_key character varying(128),
    display_name character varying(256),
    state character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    state_reason character varying(512),
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    credentials_encrypted bytea,
    credentials_alg character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    credentials_key_version integer,
    credentials_rotation_failed_at timestamp(6) with time zone,
    attention_problem character varying(32),
    attention_revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_connection_credentials_key_version CHECK (((credentials_key_version IS NULL) OR (credentials_key_version > 0))),
    CONSTRAINT ck_connection_encrypted_requires_key_version CHECK (((credentials_encrypted IS NULL) OR (credentials_key_version IS NOT NULL))),
    CONSTRAINT ck_connection_kind CHECK (((kind)::text = ANY (ARRAY[('GITHUB'::character varying)::text, ('GITLAB'::character varying)::text, ('SLACK'::character varying)::text, ('OUTLINE'::character varying)::text]))),
    CONSTRAINT ck_connection_state CHECK (((state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('SUSPENDED'::character varying)::text, ('UNINSTALLED'::character varying)::text])))
);

CREATE TABLE public.connection_activity (
    connection_id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    last_event_at timestamp with time zone,
    last_event_type character varying(128)
);

CREATE TABLE public.connection_audit (
    id bigint NOT NULL,
    connection_id bigint NOT NULL,
    event_type character varying(48) NOT NULL,
    from_state character varying(32),
    to_state character varying(32),
    actor_kind character varying(32) NOT NULL,
    actor_ref character varying(256),
    correlation_id character varying(64),
    detail text,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    actor_account_id bigint
);

ALTER TABLE public.connection_audit ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.connection_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.connection ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.connection_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.consent_decision (
    id bigint NOT NULL,
    account_id bigint,
    purpose character varying(40) NOT NULL,
    granted boolean NOT NULL,
    mechanism character varying(32) NOT NULL,
    notice_version character varying(32) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    research_organization character varying(255),
    CONSTRAINT ck_consent_decision_mechanism CHECK (((mechanism)::text = ANY (ARRAY[('FIRST_LOGIN_INTERSTITIAL'::character varying)::text, ('ACCOUNT_SETTINGS'::character varying)::text]))),
    CONSTRAINT ck_consent_decision_purpose CHECK (((purpose)::text = ANY (ARRAY[('TERMS_ACCEPTANCE'::character varying)::text, ('PRIVACY_NOTICE_ACKNOWLEDGEMENT'::character varying)::text, ('RESEARCH_PARTICIPATION'::character varying)::text])))
);

ALTER TABLE public.consent_decision ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.consent_decision_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.consumer_dead_letter (
    id bigint NOT NULL,
    workspace_id bigint,
    consumer_name character varying(255) NOT NULL,
    stream_name character varying(255) NOT NULL,
    stream_sequence bigint NOT NULL,
    subject text NOT NULL,
    received_at timestamp with time zone NOT NULL,
    replayed_by bigint,
    error_class character varying(255) NOT NULL,
    failed_at timestamp with time zone NOT NULL,
    replayed_at timestamp with time zone
);

ALTER TABLE public.consumer_dead_letter ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.consumer_dead_letter_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.event_publication (
    id uuid NOT NULL,
    listener_id text NOT NULL,
    event_type text NOT NULL,
    serialized_event text NOT NULL,
    publication_date timestamp with time zone NOT NULL,
    completion_date timestamp with time zone,
    status text,
    completion_attempts integer,
    last_resubmission_date timestamp with time zone
);

CREATE TABLE public.feedback (
    id uuid CONSTRAINT feedback_id_not_null1 NOT NULL,
    workspace_id bigint CONSTRAINT feedback_workspace_id_not_null1 NOT NULL,
    review_id uuid NOT NULL,
    item_id bigint,
    developer_id bigint NOT NULL,
    destination character varying(24) NOT NULL,
    state character varying(24) NOT NULL,
    stop_reason character varying(48),
    body text,
    checks jsonb DEFAULT '{}'::jsonb NOT NULL,
    replaces_id uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP CONSTRAINT feedback_created_at_not_null1 NOT NULL,
    delivered_at timestamp with time zone,
    CONSTRAINT ck_feedback_checks CHECK ((jsonb_typeof(checks) = 'object'::text)),
    CONSTRAINT ck_feedback_delivered CHECK ((((state)::text <> 'DELIVERED'::text) OR (delivered_at IS NOT NULL))),
    CONSTRAINT ck_feedback_destination CHECK (((destination)::text = ANY ((ARRAY['ON_WORK'::character varying, 'PRACTICE_PAGE'::character varying, 'CONVERSATION'::character varying])::text[]))),
    CONSTRAINT ck_feedback_item CHECK ((((destination)::text <> 'ON_WORK'::text) OR (item_id IS NOT NULL))),
    CONSTRAINT ck_feedback_replaces_self CHECK (((replaces_id IS NULL) OR (replaces_id <> id))),
    CONSTRAINT ck_feedback_state CHECK (((state)::text = ANY ((ARRAY['AWAITING_APPROVAL'::character varying, 'READY'::character varying, 'DELIVERED'::character varying, 'UNCONFIRMED'::character varying, 'WITHHELD'::character varying, 'REJECTED'::character varying, 'REPLACED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT ck_feedback_stop_reason CHECK (((stop_reason IS NULL) OR ((stop_reason)::text = ANY ((ARRAY['VOLUME_CAPPED'::character varying, 'COMPOSER_WITHHELD'::character varying, 'NOTHING_NEW'::character varying, 'REPEATS_DELIVERED_NOTE'::character varying, 'REACTED_DISAGREE'::character varying, 'REACTED_NOT_APPLICABLE'::character varying, 'CONVERSATION_EXPIRED'::character varying, 'DELIVERY_UNCONFIRMED'::character varying, 'ITEM_GONE'::character varying, 'ITEM_CLOSED'::character varying, 'ITEM_MERGED'::character varying, 'ITEM_STATE_INELIGIBLE'::character varying, 'ISSUE_SNAPSHOT_CHANGED'::character varying, 'PUBLIC_SUBJECT_INELIGIBLE'::character varying, 'NO_AI'::character varying, 'DELIVERY_OFF'::character varying, 'PERSON_SUPPRESSED'::character varying, 'RECIPIENT_UNAVAILABLE'::character varying, 'EMPTY_AFTER_SANITIZE'::character varying, 'INSTANCE_SILENCED'::character varying, 'FEEDBACK_OFF'::character varying, 'WORKSPACE_DISABLED'::character varying, 'WORKSPACE_DELIVERY_PAUSED'::character varying, 'STALE_ROLLOUT_REVISION'::character varying, 'OUTSIDE_CURRENT_SCOPE'::character varying, 'APPROVAL_STALE'::character varying, 'APPROVAL_NO_LONGER_ELIGIBLE'::character varying, 'PRACTICE_REQUIRES_APPROVAL'::character varying, 'BACKFILL_QUIET'::character varying, 'RESULT_MARKED_INCORRECT'::character varying, 'REVIEWED_VERSION_CHANGED'::character varying, 'REVIEW_CANCELLED'::character varying, 'EVIDENCE_NOT_PERMITTED'::character varying, 'NO_EVIDENCE'::character varying, 'UNCORROBORATED'::character varying, 'PUBLIC_HISTORY_CHANGED'::character varying, 'PUBLIC_DELIVERY_UNRESOLVED'::character varying, 'NOT_COMPOSED'::character varying])::text[])))),
    CONSTRAINT ck_feedback_withheld CHECK ((((state)::text <> 'WITHHELD'::text) OR (stop_reason IS NOT NULL)))
);

CREATE TABLE public.feedback_basis (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    feedback_id uuid NOT NULL,
    result_id uuid NOT NULL,
    problem_id uuid,
    developer_id bigint NOT NULL,
    ordinal integer NOT NULL,
    CONSTRAINT ck_feedback_basis_ordinal CHECK ((ordinal >= 0))
);

CREATE TABLE public.feedback_copy (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    feedback_id uuid NOT NULL,
    kind character varying(16) NOT NULL,
    "position" integer NOT NULL,
    body text,
    path text,
    start_line integer,
    end_line integer,
    side character varying(3),
    stop_reason character varying(48),
    readback character varying(16),
    last_error character varying(512),
    state character varying(16) DEFAULT 'PLANNED'::character varying NOT NULL,
    write_started_at timestamp with time zone,
    write_key text,
    write_marker text,
    external_ref text,
    external_url text,
    mentor_entry_id uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    posted_at timestamp with time zone,
    mentor_turn_id uuid,
    CONSTRAINT ck_feedback_copy_anchor CHECK (((((kind)::text = ANY ((ARRAY['SUMMARY'::character varying, 'CHAT_TURN'::character varying])::text[])) AND (path IS NULL) AND (start_line IS NULL) AND (end_line IS NULL)) OR (((kind)::text = 'FILE_NOTE'::text) AND (path IS NOT NULL) AND (((start_line IS NULL) AND (end_line IS NULL)) OR ((start_line IS NOT NULL) AND (end_line IS NOT NULL) AND (start_line > 0) AND (end_line >= start_line)))) OR (((kind)::text = 'LINE_NOTE'::text) AND (path IS NOT NULL) AND (start_line IS NOT NULL) AND (end_line IS NOT NULL) AND (start_line > 0) AND (end_line >= start_line)))),
    CONSTRAINT ck_feedback_copy_fence CHECK ((((state)::text <> 'WRITE_STARTED'::text) OR (write_started_at IS NOT NULL))),
    CONSTRAINT ck_feedback_copy_kind CHECK (((kind)::text = ANY ((ARRAY['SUMMARY'::character varying, 'LINE_NOTE'::character varying, 'FILE_NOTE'::character varying, 'CHAT_TURN'::character varying])::text[]))),
    CONSTRAINT ck_feedback_copy_position CHECK (("position" >= 0)),
    CONSTRAINT ck_feedback_copy_posted CHECK ((((state)::text <> ALL ((ARRAY['POSTED'::character varying, 'RETRACTED'::character varying])::text[])) OR (posted_at IS NOT NULL))),
    CONSTRAINT ck_feedback_copy_readback CHECK (((readback IS NULL) OR ((readback)::text = ANY ((ARRAY['ABSENT'::character varying, 'TRANSIENT'::character varying, 'UNKNOWN'::character varying])::text[])))),
    CONSTRAINT ck_feedback_copy_side CHECK (((side IS NULL) OR ((side)::text = ANY ((ARRAY['OLD'::character varying, 'NEW'::character varying])::text[])))),
    CONSTRAINT ck_feedback_copy_side_scope CHECK ((((kind)::text = ANY ((ARRAY['LINE_NOTE'::character varying, 'FILE_NOTE'::character varying])::text[])) OR (side IS NULL))),
    CONSTRAINT ck_feedback_copy_state CHECK (((state)::text = ANY ((ARRAY['PLANNED'::character varying, 'WRITE_STARTED'::character varying, 'POSTED'::character varying, 'FAILED'::character varying, 'WITHHELD'::character varying, 'RETRACTED'::character varying])::text[]))),
    CONSTRAINT ck_feedback_copy_stop_reason CHECK (((stop_reason IS NULL) OR ((stop_reason)::text = ANY ((ARRAY['VOLUME_CAPPED'::character varying, 'COMPOSER_WITHHELD'::character varying, 'NOTHING_NEW'::character varying, 'REPEATS_DELIVERED_NOTE'::character varying, 'REACTED_DISAGREE'::character varying, 'REACTED_NOT_APPLICABLE'::character varying, 'CONVERSATION_EXPIRED'::character varying, 'DELIVERY_UNCONFIRMED'::character varying, 'ITEM_GONE'::character varying, 'ITEM_CLOSED'::character varying, 'ITEM_MERGED'::character varying, 'ITEM_STATE_INELIGIBLE'::character varying, 'ISSUE_SNAPSHOT_CHANGED'::character varying, 'PUBLIC_SUBJECT_INELIGIBLE'::character varying, 'NO_AI'::character varying, 'DELIVERY_OFF'::character varying, 'PERSON_SUPPRESSED'::character varying, 'RECIPIENT_UNAVAILABLE'::character varying, 'EMPTY_AFTER_SANITIZE'::character varying, 'INSTANCE_SILENCED'::character varying, 'FEEDBACK_OFF'::character varying, 'WORKSPACE_DISABLED'::character varying, 'WORKSPACE_DELIVERY_PAUSED'::character varying, 'STALE_ROLLOUT_REVISION'::character varying, 'OUTSIDE_CURRENT_SCOPE'::character varying, 'APPROVAL_STALE'::character varying, 'APPROVAL_NO_LONGER_ELIGIBLE'::character varying, 'PRACTICE_REQUIRES_APPROVAL'::character varying, 'BACKFILL_QUIET'::character varying, 'RESULT_MARKED_INCORRECT'::character varying, 'REVIEWED_VERSION_CHANGED'::character varying, 'REVIEW_CANCELLED'::character varying, 'EVIDENCE_NOT_PERMITTED'::character varying, 'NO_EVIDENCE'::character varying, 'UNCORROBORATED'::character varying, 'PUBLIC_HISTORY_CHANGED'::character varying, 'PUBLIC_DELIVERY_UNRESOLVED'::character varying, 'NOT_COMPOSED'::character varying])::text[])))),
    CONSTRAINT ck_feedback_copy_summary_body CHECK ((((kind)::text <> 'SUMMARY'::text) OR (body IS NULL)))
);

CREATE TABLE public.feedback_decision (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    feedback_id uuid NOT NULL,
    action character varying(8) NOT NULL,
    actor_account_id bigint,
    reason character varying(500),
    note text,
    content_digest character varying(64),
    decided_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_feedback_decision_action CHECK (((action)::text = ANY ((ARRAY['APPROVE'::character varying, 'REJECT'::character varying, 'WITHDRAW'::character varying, 'RESTORE'::character varying])::text[]))),
    CONSTRAINT ck_feedback_decision_approval_digest CHECK ((((action)::text <> ALL ((ARRAY['APPROVE'::character varying, 'REJECT'::character varying])::text[])) OR (content_digest IS NOT NULL))),
    CONSTRAINT ck_feedback_decision_approve_reason CHECK ((((action)::text <> 'APPROVE'::text) OR (reason IS NULL))),
    CONSTRAINT ck_feedback_decision_digest CHECK (((content_digest IS NULL) OR ((content_digest)::text ~ '^[0-9a-f]{64}$'::text)))
);

CREATE TABLE public.feedback_response (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    feedback_id uuid NOT NULL,
    resolution character varying(16),
    usefulness character varying(16),
    comment text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_feedback_response_disagree CHECK ((((resolution)::text IS DISTINCT FROM 'DISAGREE'::text) OR (NULLIF(btrim(comment), ''::text) IS NOT NULL))),
    CONSTRAINT ck_feedback_response_resolution CHECK (((resolution IS NULL) OR ((resolution)::text = ANY ((ARRAY['ADDRESSED'::character varying, 'DISAGREE'::character varying, 'NOT_APPLICABLE'::character varying])::text[])))),
    CONSTRAINT ck_feedback_response_usefulness CHECK (((usefulness IS NULL) OR ((usefulness)::text = ANY ((ARRAY['HELPFUL'::character varying, 'UNHELPFUL'::character varying])::text[]))))
);

CREATE TABLE public.feedback_send (
    feedback_id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    action character varying(8) DEFAULT 'POST'::character varying NOT NULL,
    state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    lease_owner character varying(64),
    lease_expires_at timestamp with time zone,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_error character varying(512),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_feedback_send_action CHECK (((action)::text = ANY ((ARRAY['POST'::character varying, 'RETRACT'::character varying])::text[]))),
    CONSTRAINT ck_feedback_send_attempts CHECK ((attempts >= 0)),
    CONSTRAINT ck_feedback_send_lease CHECK ((((state)::text = 'SENDING'::text) = ((lease_owner IS NOT NULL) AND (lease_expires_at IS NOT NULL)))),
    CONSTRAINT ck_feedback_send_lease_pair CHECK (((lease_owner IS NULL) = (lease_expires_at IS NULL))),
    CONSTRAINT ck_feedback_send_state CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'SENDING'::character varying, 'UNCERTAIN'::character varying, 'DONE'::character varying])::text[])))
);

CREATE TABLE public.fx_rate (
    id bigint NOT NULL,
    rate_date date NOT NULL,
    usd_per_eur numeric(12,6) NOT NULL,
    fetched_at timestamp with time zone NOT NULL
);

ALTER TABLE public.fx_rate ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.fx_rate_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.git_commit (
    id bigint NOT NULL,
    sha character varying(40) NOT NULL,
    message character varying(1024) NOT NULL,
    message_body text,
    html_url character varying(512),
    authored_at timestamp with time zone NOT NULL,
    committed_at timestamp with time zone NOT NULL,
    additions integer NOT NULL,
    deletions integer NOT NULL,
    changed_files integer NOT NULL,
    author_email character varying(255),
    committer_email character varying(255),
    signature_valid boolean,
    authored_by_committer boolean,
    committed_via_web boolean,
    parent_count integer,
    last_sync_at timestamp with time zone,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    repository_id bigint NOT NULL,
    author_id bigint,
    committer_id bigint,
    signature_state character varying(32),
    signature_was_signed_by_github boolean,
    signature_signer_login character varying(255),
    parent_shas text,
    status_check_rollup_state character varying(32),
    on_behalf_of_login character varying(255),
    git_details_captured_at timestamp(6) with time zone
);

ALTER TABLE public.git_commit ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.git_commit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.identity_provider (
    id bigint CONSTRAINT git_provider_id_not_null NOT NULL,
    type character varying(10) CONSTRAINT git_provider_type_not_null NOT NULL,
    server_url character varying(512) CONSTRAINT git_provider_server_url_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT git_provider_created_at_not_null NOT NULL
);

ALTER TABLE public.identity_provider ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.git_provider_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.identity (
    id bigint NOT NULL,
    account_id bigint NOT NULL,
    provider_id bigint NOT NULL,
    provider_user_key character varying(255) NOT NULL,
    team_key character varying(255) DEFAULT ''::character varying NOT NULL,
    linked_via character varying(16) NOT NULL,
    linked_at timestamp with time zone DEFAULT now() NOT NULL,
    last_login_at timestamp with time zone,
    disabled_at timestamp with time zone,
    CONSTRAINT ck_identity_key CHECK ((btrim((provider_user_key)::text) <> ''::text)),
    CONSTRAINT ck_identity_linked_via CHECK (((linked_via)::text = ANY ((ARRAY['ROSTER'::character varying, 'SIGN_IN'::character varying, 'MANUAL'::character varying])::text[])))
);

ALTER TABLE public.identity ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.identity_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.identity_restriction (
    id uuid NOT NULL,
    provider_id bigint NOT NULL,
    provider_user_key character varying(255) NOT NULL,
    team_key character varying(255) DEFAULT ''::character varying NOT NULL,
    kind character varying(16) NOT NULL,
    active_request_id uuid,
    CONSTRAINT ck_identity_restriction_kind CHECK (((kind)::text = ANY ((ARRAY['ERASED'::character varying, 'HIDE_PUBLIC'::character varying])::text[]))),
    CONSTRAINT ck_identity_restriction_request CHECK ((((kind)::text = 'ERASED'::text) OR (active_request_id IS NULL)))
);

CREATE TABLE public.instance_llm_settings (
    id smallint NOT NULL,
    allowed_egress_hosts text,
    allow_workspace_connections boolean DEFAULT true NOT NULL,
    updated_at timestamp with time zone,
    updated_by_account_id bigint,
    CONSTRAINT ck_instance_llm_settings_singleton CHECK ((id = 1))
);

CREATE TABLE public.instance_settings (
    id bigint NOT NULL,
    silent_mode_engaged boolean DEFAULT true NOT NULL,
    silent_mode_reason character varying(500),
    silent_mode_changed_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    silent_mode_changed_by_account_id bigint,
    public_activity_allowed boolean,
    CONSTRAINT ck_instance_settings_singleton CHECK ((id = 1))
);

CREATE TABLE public.issue (
    issue_type character varying(31) NOT NULL,
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    closed_at timestamp with time zone,
    comments_count integer NOT NULL,
    html_url character varying(255),
    is_locked boolean NOT NULL,
    number integer NOT NULL,
    state character varying(255),
    title character varying(1024),
    additions integer,
    changed_files integer,
    commits integer,
    deletions integer,
    is_draft boolean,
    is_merged boolean,
    merged_at timestamp with time zone,
    author_id bigint,
    milestone_id bigint,
    repository_id bigint,
    merged_by_id bigint,
    last_sync_at timestamp with time zone,
    state_reason character varying(32),
    body text,
    parent_issue_id bigint,
    sub_issues_total integer,
    sub_issues_completed integer,
    sub_issues_percent_completed integer,
    issue_type_id character varying(128),
    merge_state_status character varying(255),
    mergeable boolean,
    review_decision character varying(255),
    head_ref_name character varying(255),
    head_ref_oid character varying(40),
    base_ref_name character varying(255),
    base_ref_oid character varying(40),
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL,
    merge_commit_sha character varying(40),
    deleted_at timestamp with time zone,
    head_check_sha character varying(40),
    head_check_state character varying(16),
    reviewers_observed_at timestamp with time zone,
    head_check_observed_at timestamp(6) with time zone,
    discussions_observed_at timestamp(6) with time zone,
    head_check_runs jsonb,
    CONSTRAINT issue_merge_state_status_check CHECK (((merge_state_status)::text = ANY (ARRAY['BEHIND'::text, 'BLOCKED'::text, 'CLEAN'::text, 'DIRTY'::text, 'HAS_HOOKS'::text, 'UNKNOWN'::text, 'UNSTABLE'::text]))),
    CONSTRAINT issue_review_decision_check CHECK (((review_decision)::text = ANY (ARRAY['APPROVED'::text, 'CHANGES_REQUESTED'::text, 'REVIEW_REQUIRED'::text])))
);

CREATE TABLE public.issue_assignee (
    issue_id bigint NOT NULL,
    user_id bigint NOT NULL
);

CREATE TABLE public.issue_blocking (
    blocked_issue_id bigint NOT NULL,
    blocking_issue_id bigint NOT NULL
);

CREATE TABLE public.issue_comment (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    author_association character varying(255),
    html_url character varying(255),
    author_id bigint,
    issue_id bigint,
    body text,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

ALTER TABLE public.issue_comment ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.issue_comment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.issue ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.issue_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.issue_label (
    issue_id bigint NOT NULL,
    label_id bigint NOT NULL
);

CREATE TABLE public.issue_type (
    id character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    description text,
    color character varying(20) NOT NULL,
    is_enabled boolean NOT NULL,
    organization_id bigint NOT NULL,
    last_sync_at timestamp with time zone
);

CREATE TABLE public.issued_jwt (
    jti uuid NOT NULL,
    account_id bigint NOT NULL,
    issued_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    revoked_reason character varying(32),
    user_agent character varying(512),
    ip_inet inet,
    refresh_token_hash character varying(64),
    session_id uuid,
    predecessor_jti uuid,
    used_at timestamp with time zone
);

CREATE TABLE public.item (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    kind character varying(64) NOT NULL,
    parent_id bigint,
    source_id bigint,
    path text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    removed_at timestamp with time zone,
    CONSTRAINT ck_item_kind CHECK (((kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT ck_item_parent CHECK ((parent_id IS DISTINCT FROM id)),
    CONSTRAINT ck_item_path CHECK (((path IS NULL) OR ((btrim(path) <> ''::text) AND (parent_id IS NOT NULL)))),
    CONSTRAINT ck_item_positive_source CHECK (((source_id IS NULL) OR (source_id > 0))),
    CONSTRAINT ck_item_source CHECK (((path IS NULL) <> (source_id IS NULL)))
);

CREATE TABLE public.item_event (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    item_id bigint NOT NULL,
    name character varying(128) NOT NULL,
    item_version text,
    occurred_at timestamp with time zone NOT NULL,
    found_by character varying(16) NOT NULL,
    actor_user_id bigint,
    requested_by_id bigint,
    state character varying(16) DEFAULT 'RECORDED'::character varying NOT NULL,
    reason character varying(48),
    decided_at timestamp with time zone NOT NULL,
    last_attempted_at timestamp with time zone,
    retry_at timestamp with time zone,
    pending_fence character varying(1024),
    evidence jsonb,
    evidence_expires_at timestamp(6) with time zone,
    source_key character varying(512),
    CONSTRAINT ck_item_event_found_by CHECK (((found_by)::text = ANY ((ARRAY['WEBHOOK'::character varying, 'SYNC'::character varying, 'REQUEST'::character varying, 'BACKFILL'::character varying, 'SCHEDULE'::character varying])::text[]))),
    CONSTRAINT ck_item_event_name CHECK (((name)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT ck_item_event_reason CHECK (((reason IS NULL) OR ((reason)::text = ANY ((ARRAY['GATE_WITHHELD'::character varying, 'COOLDOWN_ACTIVE'::character varying, 'REQUEST_COOLDOWN_ACTIVE'::character varying, 'REQUESTER_QUOTA_EXHAUSTED'::character varying, 'CONCURRENT_DUPLICATE'::character varying, 'COALESCED'::character varying, 'OUT_OF_REVIEW_SCOPE'::character varying, 'BOT_AUTHOR'::character varying, 'BOT_REVIEWER'::character varying, 'STALE_ROLLOUT_REVISION'::character varying, 'WORKSPACE_INACTIVE'::character varying, 'PRACTICES_DISABLED'::character varying, 'NO_ACTIVE_PRACTICE'::character varying, 'REVIEW_MODEL_UNBOUND'::character varying, 'MEMBER_AI_DECLINED'::character varying, 'PERSON_DATA_ERASED'::character varying, 'FEEDBACK_MODE_OFF'::character varying, 'SUBJECT_UNLINKED'::character varying, 'MERGE_ACTOR_UNAVAILABLE'::character varying, 'MODEL_UNAVAILABLE'::character varying, 'BUDGET_EXHAUSTED'::character varying, 'ITEM_NOT_VISIBLE'::character varying, 'VERSION_SUPERSEDED'::character varying, 'PENDING_DEADLINE_EXCEEDED'::character varying, 'ITEM_GONE'::character varying, 'LEGACY_REVIEW_UNBOUND'::character varying, 'OCCASION_EVIDENCE_UNAVAILABLE'::character varying, 'QUIET_WINDOW'::character varying, 'REVIEW_IN_PROGRESS'::character varying, 'ALREADY_CURRENT'::character varying])::text[])))),
    CONSTRAINT ck_item_event_state CHECK (((state)::text = ANY ((ARRAY['RECORDED'::character varying, 'WAITING'::character varying, 'STARTED'::character varying, 'WITHHELD'::character varying, 'EXPIRED'::character varying])::text[]))),
    CONSTRAINT ck_item_event_version CHECK ((btrim(item_version) <> ''::text)),
    CONSTRAINT ck_item_event_version_or_fence CHECK (((item_version IS NOT NULL) OR (pending_fence IS NOT NULL)))
);

ALTER TABLE public.item ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.jwt_signing_key (
    kid character varying(64) NOT NULL,
    algorithm character varying(16) DEFAULT 'ES256'::character varying NOT NULL,
    public_key_pem text NOT NULL,
    private_key_pem bytea NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    encryption_key_id character varying(64) NOT NULL
);

CREATE TABLE public.label (
    id bigint NOT NULL,
    color character varying(255),
    description character varying(255),
    name character varying(255),
    repository_id bigint,
    last_sync_at timestamp with time zone,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

ALTER TABLE public.label ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.label_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.llm_call_permit (
    id uuid NOT NULL,
    acquired_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    call_class character varying(16) NOT NULL,
    pool_id bigint NOT NULL,
    renewed_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    worker_id character varying(255) NOT NULL,
    workspace_id bigint,
    source_id uuid NOT NULL,
    attempt integer NOT NULL,
    state character varying(16) DEFAULT 'WAITING'::character varying NOT NULL,
    granted_at timestamp(6) with time zone,
    closed_at timestamp(6) with time zone,
    requests integer DEFAULT 0 NOT NULL,
    tokens bigint DEFAULT 0 NOT NULL
);

CREATE TABLE public.llm_call_pool (
    id bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    display_name character varying(128) NOT NULL,
    interactive_reserve integer DEFAULT 0 NOT NULL,
    max_concurrent_calls integer,
    no_call_before timestamp(6) with time zone,
    slug character varying(64) NOT NULL,
    updated_at timestamp(6) with time zone,
    requests_per_minute integer,
    tokens_per_minute bigint,
    tokens_per_call bigint
);

ALTER TABLE public.llm_call_pool ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.llm_call_pool_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.llm_connection (
    id bigint NOT NULL,
    slug character varying(64) NOT NULL,
    display_name character varying(128) NOT NULL,
    base_url character varying(2048) NOT NULL,
    api_protocol character varying(40) NOT NULL,
    auth_mode character varying(16) DEFAULT 'BEARER'::character varying NOT NULL,
    api_key text,
    enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone,
    connection_platform character varying(32),
    CONSTRAINT ck_llm_connection_api_protocol CHECK (((api_protocol)::text = ANY ((ARRAY['openai-completions'::character varying, 'openai-responses'::character varying, 'openai-decisions'::character varying, 'openai-embeddings'::character varying, 'cohere-rerank'::character varying, 'anthropic-messages'::character varying])::text[]))),
    CONSTRAINT ck_llm_connection_auth_mode CHECK (((auth_mode)::text = ANY (ARRAY[('BEARER'::character varying)::text, ('API_KEY'::character varying)::text])))
);

ALTER TABLE public.llm_connection ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.llm_connection_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.llm_model (
    id bigint NOT NULL,
    connection_id bigint NOT NULL,
    slug character varying(64) NOT NULL,
    display_name character varying(128) NOT NULL,
    upstream_model_id character varying(256) NOT NULL,
    context_window integer,
    max_output_tokens integer,
    visibility character varying(16) DEFAULT 'GRANTED'::character varying NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone,
    reasoning_effort character varying(16),
    data_handling_note character varying(200),
    operated_by character varying(24),
    brand character varying(16),
    call_pool_id bigint,
    forced_tool_choice boolean,
    CONSTRAINT ck_llm_model_operated_by CHECK (((operated_by)::text = ANY ((ARRAY['OWN_ORGANISATION'::character varying, 'PROVIDER'::character varying])::text[]))),
    CONSTRAINT ck_llm_model_visibility CHECK (((visibility)::text = ANY (ARRAY[('PUBLIC'::character varying)::text, ('GRANTED'::character varying)::text])))
);

ALTER TABLE public.llm_model ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.llm_model_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.llm_model_price (
    id bigint NOT NULL,
    model_id bigint NOT NULL,
    pricing_mode character varying(16) NOT NULL,
    per_1m_input_usd numeric(18,8),
    per_1m_output_usd numeric(18,8),
    per_1m_cache_read_usd numeric(18,8),
    per_1m_cache_write_usd numeric(18,8),
    currency character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    note character varying(500),
    effective_from timestamp with time zone NOT NULL,
    effective_to timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_llm_model_price_mode CHECK (((pricing_mode)::text = ANY (ARRAY[('PRICED'::character varying)::text, ('NO_CHARGE'::character varying)::text, ('UNPRICED'::character varying)::text]))),
    CONSTRAINT ck_llm_model_price_shape CHECK (((((pricing_mode)::text = 'PRICED'::text) AND (per_1m_input_usd IS NOT NULL) AND (per_1m_input_usd >= (0)::numeric) AND (per_1m_output_usd IS NOT NULL) AND (per_1m_output_usd >= (0)::numeric) AND ((per_1m_cache_read_usd IS NULL) OR (per_1m_cache_read_usd >= (0)::numeric)) AND ((per_1m_cache_write_usd IS NULL) OR (per_1m_cache_write_usd >= (0)::numeric)) AND (GREATEST(per_1m_input_usd, per_1m_output_usd, COALESCE(per_1m_cache_read_usd, (0)::numeric), COALESCE(per_1m_cache_write_usd, (0)::numeric)) > (0)::numeric)) OR (((pricing_mode)::text = 'NO_CHARGE'::text) AND (note IS NOT NULL) AND (btrim((note)::text) <> ''::text) AND (per_1m_input_usd IS NULL) AND (per_1m_output_usd IS NULL) AND (per_1m_cache_read_usd IS NULL) AND (per_1m_cache_write_usd IS NULL)) OR (((pricing_mode)::text = 'UNPRICED'::text) AND (per_1m_input_usd IS NULL) AND (per_1m_output_usd IS NULL) AND (per_1m_cache_read_usd IS NULL) AND (per_1m_cache_write_usd IS NULL)))),
    CONSTRAINT ck_llm_model_price_window CHECK (((effective_to IS NULL) OR (effective_to > effective_from)))
);

ALTER TABLE public.llm_model_price ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.llm_model_price_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.llm_model_workspace_grant (
    model_id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    granted_at timestamp with time zone NOT NULL
);

CREATE TABLE public.llm_usage_event (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    job_type character varying(40) NOT NULL,
    source_type character varying(32) NOT NULL,
    source_id uuid NOT NULL,
    source_attempt integer DEFAULT 0 NOT NULL,
    model character varying(128),
    input_tokens bigint DEFAULT 0 NOT NULL,
    output_tokens bigint DEFAULT 0 NOT NULL,
    cache_read_tokens bigint DEFAULT 0 NOT NULL,
    cache_write_tokens bigint DEFAULT 0 NOT NULL,
    reasoning_tokens bigint DEFAULT 0 NOT NULL,
    total_calls integer DEFAULT 1 NOT NULL,
    cost_usd numeric(18,6),
    pricing_state character varying(16) DEFAULT 'UNPRICED'::character varying NOT NULL,
    funding_source character varying(16) DEFAULT 'INSTANCE'::character varying NOT NULL,
    applied_price_id bigint,
    applied_workspace_model_id bigint,
    applied_per_1m_input_usd numeric(18,8),
    applied_per_1m_output_usd numeric(18,8),
    applied_per_1m_cache_read_usd numeric(18,8),
    applied_per_1m_cache_write_usd numeric(18,8),
    occurred_at timestamp with time zone NOT NULL,
    usage_provenance character varying(16),
    measurement_verification character varying(16) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    review_id uuid,
    search_units bigint DEFAULT 0 NOT NULL,
    unreported_calls integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_llm_usage_event_counts CHECK (((search_units >= 0) AND (unreported_calls >= 0))),
    CONSTRAINT ck_llm_usage_event_funding_source CHECK (((funding_source)::text = ANY (ARRAY[('INSTANCE'::character varying)::text, ('WORKSPACE'::character varying)::text]))),
    CONSTRAINT ck_llm_usage_event_measurement_verification CHECK (((measurement_verification)::text = ANY ((ARRAY['UNKNOWN'::character varying, 'VERIFIED'::character varying, 'UNVERIFIABLE'::character varying])::text[]))),
    CONSTRAINT ck_llm_usage_event_pricing_state CHECK (((pricing_state)::text = ANY (ARRAY[('PRICED'::character varying)::text, ('NO_CHARGE'::character varying)::text, ('UNPRICED'::character varying)::text]))),
    CONSTRAINT ck_llm_usage_event_source_attempt CHECK ((source_attempt >= 0)),
    CONSTRAINT ck_llm_usage_event_source_type CHECK (((source_type)::text = ANY ((ARRAY['REVIEW_JOB'::character varying, 'MENTOR_TURN'::character varying, 'PRACTICE_SCRIPT_DECISION'::character varying, 'PRACTICE_SCRIPT_EMBEDDING'::character varying, 'PRACTICE_SCRIPT_RERANKING'::character varying])::text[]))),
    CONSTRAINT ck_llm_usage_event_unpriced_has_no_cost CHECK ((((pricing_state)::text = 'UNPRICED'::text) = (cost_usd IS NULL))),
    CONSTRAINT ck_llm_usage_event_usage_provenance CHECK (((usage_provenance IS NULL) OR ((usage_provenance)::text = ANY (ARRAY[('RUNNER'::character varying)::text, ('PROXY'::character varying)::text, ('MERGED'::character varying)::text, ('NONE'::character varying)::text]))))
);

CREATE TABLE public.login_provider (
    id bigint NOT NULL,
    registration_id character varying(64) NOT NULL,
    type character varying(10) NOT NULL,
    display_name character varying(255) NOT NULL,
    base_url character varying(512) NOT NULL,
    client_id character varying(512) NOT NULL,
    client_secret text NOT NULL,
    scopes character varying(512) NOT NULL,
    enabled boolean NOT NULL,
    seeded_from_env boolean NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_login_provider_type CHECK (((type)::text = ANY (ARRAY[('GITHUB'::character varying)::text, ('GITLAB'::character varying)::text, ('SLACK'::character varying)::text, ('OUTLINE'::character varying)::text])))
);

ALTER TABLE public.login_provider ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.login_provider_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.membership (
    workspace_id bigint NOT NULL,
    account_id bigint NOT NULL,
    role character varying(16) DEFAULT 'MEMBER'::character varying NOT NULL,
    hidden boolean DEFAULT false NOT NULL,
    review_selected boolean DEFAULT false NOT NULL,
    onboarding_seen_revision bigint,
    public_activity_seen boolean DEFAULT false NOT NULL,
    joined_at timestamp with time zone DEFAULT now() NOT NULL,
    left_at timestamp with time zone,
    CONSTRAINT ck_membership_dates CHECK (((left_at IS NULL) OR (left_at >= joined_at))),
    CONSTRAINT ck_membership_role CHECK (((role)::text = ANY ((ARRAY['OWNER'::character varying, 'ADMIN'::character varying, 'MEMBER'::character varying])::text[]))),
    CONSTRAINT ck_membership_seen_revision CHECK (((onboarding_seen_revision IS NULL) OR (onboarding_seen_revision >= 0)))
);

CREATE TABLE public.mentor_conversation (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    last_activity_at timestamp(6) with time zone NOT NULL,
    parent_conversation_id uuid,
    parent_entry_id uuid,
    slack_channel_id character varying(32),
    slack_team_id character varying(32),
    slack_user_id character varying(32),
    account_id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    last_read_at timestamp(6) with time zone,
    CONSTRAINT chk_mentor_conversation_slack CHECK ((((slack_team_id IS NULL) AND (slack_user_id IS NULL) AND (slack_channel_id IS NULL)) OR ((slack_team_id IS NOT NULL) AND (slack_user_id IS NOT NULL) AND (slack_channel_id IS NOT NULL) AND (parent_conversation_id IS NULL)))),
    CONSTRAINT chk_mentor_conversation_thread CHECK (((parent_conversation_id IS NULL) = (parent_entry_id IS NULL)))
);

CREATE TABLE public.mentor_entry (
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    edited_at timestamp(6) with time zone,
    kind character varying(16) NOT NULL,
    payload jsonb,
    redacted_at timestamp(6) with time zone,
    request_id character varying(200),
    search_text text DEFAULT ''::text NOT NULL,
    seq bigint NOT NULL,
    slack_attempts integer DEFAULT 0 NOT NULL,
    slack_delivery character varying(16),
    slack_stream_ts character varying(32),
    slack_ts character varying(32),
    surface character varying(8) NOT NULL,
    text text NOT NULL,
    turn_id uuid,
    vote character varying(16),
    workspace_id bigint NOT NULL,
    CONSTRAINT chk_mentor_entry_kind CHECK (((kind)::text = ANY ((ARRAY['USER'::character varying, 'HEPH'::character varying, 'TOOL_RESULT'::character varying, 'SYSTEM'::character varying, 'SUMMARY'::character varying, 'DIGEST'::character varying])::text[]))),
    CONSTRAINT chk_mentor_entry_payload_shape CHECK (((payload IS NULL) OR (jsonb_typeof(payload) = 'object'::text))),
    CONSTRAINT chk_mentor_entry_slack_delivery CHECK (((slack_delivery IS NULL) OR ((slack_delivery)::text = ANY ((ARRAY['PENDING'::character varying, 'DELIVERED'::character varying, 'UNCERTAIN'::character varying, 'FAILED'::character varying])::text[])))),
    CONSTRAINT chk_mentor_entry_surface CHECK (((surface)::text = ANY ((ARRAY['APP'::character varying, 'SLACK'::character varying, 'SYSTEM'::character varying])::text[]))),
    CONSTRAINT chk_mentor_entry_vote CHECK (((vote IS NULL) OR ((vote)::text = ANY ((ARRAY['HELPFUL'::character varying, 'NOT_HELPFUL'::character varying])::text[]))))
);

CREATE TABLE public.mentor_turn (
    id uuid NOT NULL,
    admission jsonb,
    attempt integer DEFAULT 0 NOT NULL,
    cache_write_tokens bigint DEFAULT 0 NOT NULL,
    cached_tokens bigint DEFAULT 0 NOT NULL,
    conversation_id uuid NOT NULL,
    cost_usd numeric(19,9),
    created_at timestamp(6) with time zone NOT NULL,
    error text,
    finished_at timestamp(6) with time zone,
    input_tokens bigint DEFAULT 0 NOT NULL,
    leased_until timestamp(6) with time zone,
    llm_calls integer DEFAULT 0 NOT NULL,
    output_tokens bigint DEFAULT 0 NOT NULL,
    reasoning_tokens bigint DEFAULT 0 NOT NULL,
    request_id character varying(200) NOT NULL,
    started_at timestamp(6) with time zone,
    status character varying(16) NOT NULL,
    surface character varying(8) NOT NULL,
    workspace_id bigint NOT NULL,
    page_context character varying(500),
    failed_call character varying(64),
    failed_call_status integer,
    failure_cause character varying(200),
    queued_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    lease_id uuid,
    CONSTRAINT chk_mentor_turn_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'DONE'::character varying, 'FAILED'::character varying, 'STOPPED'::character varying])::text[]))),
    CONSTRAINT chk_mentor_turn_surface CHECK (((surface)::text = ANY ((ARRAY['APP'::character varying, 'SLACK'::character varying])::text[]))),
    CONSTRAINT ck_mentor_turn_lease_id CHECK (((lease_id IS NULL) OR ((leased_until IS NOT NULL) AND ((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'STOPPED'::character varying])::text[])))))
)
WITH (fillfactor='50');

CREATE TABLE public.mentor_turn_attempt_usage (
    attempt integer NOT NULL,
    turn_id uuid NOT NULL,
    applied_per_1m_cache_read_usd numeric(18,8),
    applied_per_1m_cache_write_usd numeric(18,8),
    applied_per_1m_input_usd numeric(18,8),
    applied_per_1m_output_usd numeric(18,8),
    applied_price_id bigint,
    applied_workspace_model_id bigint,
    cache_read_tokens bigint DEFAULT 0 NOT NULL,
    cache_write_tokens bigint DEFAULT 0 NOT NULL,
    calls integer DEFAULT 0 NOT NULL,
    funding_source character varying(16) NOT NULL,
    input_tokens bigint DEFAULT 0 NOT NULL,
    model character varying(128) NOT NULL,
    occurred_at timestamp(6) with time zone,
    output_tokens bigint DEFAULT 0 NOT NULL,
    pricing_state character varying(16) NOT NULL,
    reasoning_tokens bigint DEFAULT 0 NOT NULL,
    workspace_id bigint NOT NULL
);

CREATE TABLE public.milestone (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    closed_at timestamp with time zone,
    due_on timestamp with time zone,
    html_url character varying(255),
    number integer NOT NULL,
    state character varying(255),
    title character varying(255),
    creator_id bigint,
    repository_id bigint,
    description text,
    closed_issues_count integer NOT NULL,
    open_issues_count integer NOT NULL,
    last_sync_at timestamp with time zone,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

ALTER TABLE public.milestone ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.milestone_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.notification_subscription (
    id uuid NOT NULL,
    account_id bigint NOT NULL,
    enabled boolean NOT NULL,
    enabled_since timestamp(6) with time zone,
    kind character varying(32) NOT NULL,
    unsubscribe_token uuid NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE public.oauth_state_nonce (
    nonce character varying(32) NOT NULL,
    workspace_id bigint NOT NULL,
    kind character varying(48) NOT NULL,
    issued_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    actor_account_id bigint
);

CREATE TABLE public.organization (
    id bigint NOT NULL,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    avatar_url character varying(255),
    native_id bigint NOT NULL,
    html_url character varying(255),
    login character varying(255) NOT NULL,
    name character varying(255),
    last_sync_at timestamp with time zone,
    provider_id bigint NOT NULL
);

ALTER TABLE public.organization ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.organization_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.organization_membership (
    organization_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role character varying(32) NOT NULL
);

CREATE TABLE public.outline_collection (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    connection_id bigint NOT NULL,
    collection_id character varying(64) NOT NULL,
    name character varying(1024),
    url_id character varying(512),
    color character varying(32),
    icon character varying(64),
    state character varying(16) NOT NULL,
    sync_status character varying(16) NOT NULL,
    documents_synced_at timestamp with time zone,
    last_sync_error character varying(2048),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    documents_upstream integer,
    exports_skipped_for_budget integer,
    description character varying(2048),
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_outline_collection_state CHECK (((state)::text = ANY (ARRAY[('ENABLED'::character varying)::text, ('PAUSED'::character varying)::text]))),
    CONSTRAINT ck_outline_collection_sync_status CHECK (((sync_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('COMPLETE'::character varying)::text])))
);

ALTER TABLE public.outline_collection ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.outline_collection_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.outline_document (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    connection_id bigint NOT NULL,
    document_id character varying(64) NOT NULL,
    collection_id character varying(64) NOT NULL,
    collection_slug character varying(512),
    parent_document_id character varying(64),
    title character varying(1024),
    slug character varying(512),
    body_markdown text,
    content_hash character varying(64),
    outline_updated_at timestamp with time zone,
    deleted_at timestamp with time zone,
    last_materialized_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by_subject character varying(64),
    created_by_name character varying(255),
    updated_by_subject character varying(64),
    updated_by_name character varying(255),
    outline_created_at timestamp with time zone,
    collaborator_subjects jsonb,
    body_evicted_at timestamp with time zone,
    archived_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_outline_document_tombstone CHECK (((deleted_at IS NULL) OR ((body_markdown IS NULL) AND (content_hash IS NULL) AND (created_by_subject IS NULL) AND (updated_by_subject IS NULL) AND (collaborator_subjects IS NULL))))
);

ALTER TABLE public.outline_document ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.outline_document_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.passkey_challenge (
    id uuid NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    options_json text NOT NULL,
    purpose character varying(16) NOT NULL,
    token_id uuid NOT NULL,
    account_id bigint NOT NULL
);

CREATE TABLE public.passkey_credential (
    id character varying(2048) NOT NULL,
    record_json text NOT NULL,
    account_id bigint NOT NULL
);

CREATE TABLE public.passkey_recovery_code (
    hash character varying(64) NOT NULL,
    account_id bigint NOT NULL
);

CREATE TABLE public.person_data_request (
    id uuid NOT NULL,
    administrator_account_id bigint,
    completed_at timestamp(6) with time zone,
    completed_json text NOT NULL,
    counts_json text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    failure_code character varying(48),
    scope_json text,
    selections_json text,
    state character varying(24) NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE public.person_evidence_copy (
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    state character varying(24) NOT NULL,
    payload jsonb NOT NULL,
    store_id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.practice (
    id bigint CONSTRAINT practice_id_not_null1 NOT NULL,
    workspace_id bigint CONSTRAINT practice_workspace_id_not_null1 NOT NULL,
    slug character varying(64) CONSTRAINT practice_slug_not_null1 NOT NULL,
    kind character varying(64) NOT NULL,
    group_id bigint,
    "position" integer DEFAULT 0 NOT NULL,
    current_version_id bigint NOT NULL,
    feedback_mode character varying(16),
    catalog_slug character varying(64),
    catalog_base jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP CONSTRAINT practice_created_at_not_null1 NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT ck_practice_catalog CHECK (((catalog_slug IS NULL) = (catalog_base IS NULL))),
    CONSTRAINT ck_practice_catalog_base CHECK (((catalog_base IS NULL) OR (jsonb_typeof(catalog_base) = 'object'::text))),
    CONSTRAINT ck_practice_feedback_mode CHECK (((feedback_mode IS NULL) OR ((feedback_mode)::text = ANY ((ARRAY['OFF'::character varying, 'APPROVE_FIRST'::character varying, 'AUTOMATIC'::character varying])::text[])))),
    CONSTRAINT ck_practice_kind CHECK (((kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT ck_practice_position CHECK (("position" >= 0))
);

CREATE TABLE public.practice_group (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slug character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    description text,
    icon character varying(64),
    color character varying(32),
    "position" integer DEFAULT 0 NOT NULL,
    shown_to_developers boolean DEFAULT true NOT NULL,
    feedback_mode character varying(16),
    catalog_slug character varying(64),
    catalog_base jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT ck_practice_group_catalog CHECK (((catalog_slug IS NULL) = (catalog_base IS NULL))),
    CONSTRAINT ck_practice_group_catalog_base CHECK (((catalog_base IS NULL) OR (jsonb_typeof(catalog_base) = 'object'::text))),
    CONSTRAINT ck_practice_group_feedback_mode CHECK (((feedback_mode IS NULL) OR ((feedback_mode)::text = ANY ((ARRAY['OFF'::character varying, 'APPROVE_FIRST'::character varying, 'AUTOMATIC'::character varying])::text[])))),
    CONSTRAINT ck_practice_group_position CHECK (("position" >= 0))
);

ALTER TABLE public.practice_group ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.practice_group_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.practice ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.practice_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.practice_version (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    practice_id bigint NOT NULL,
    number integer NOT NULL,
    definition jsonb NOT NULL,
    name character varying(128) GENERATED ALWAYS AS (((definition ->> 'name'::text))::character varying(128)) STORED,
    review_digest character varying(96) GENERATED ALWAYS AS (public.practice_review_digest(definition)) STORED NOT NULL,
    created_by_account_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_practice_version_definition CHECK (((jsonb_typeof(definition) = 'object'::text) AND jsonb_exists(definition, 'criteria'::text))),
    CONSTRAINT ck_practice_version_digest CHECK (((review_digest)::text ~ '^v6:[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_practice_version_number CHECK ((number >= 1))
);

ALTER TABLE public.practice_version ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.practice_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.problem (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    result_id uuid NOT NULL,
    "position" integer NOT NULL,
    key character varying(64),
    severity character varying(16) NOT NULL,
    summary character varying(255) NOT NULL,
    explanation text NOT NULL,
    citations jsonb NOT NULL,
    basis jsonb,
    next_step text,
    CONSTRAINT ck_problem_basis CHECK (((basis IS NULL) OR (jsonb_typeof(basis) = 'object'::text))),
    CONSTRAINT ck_problem_citations CHECK ((jsonb_typeof(citations) = 'array'::text)),
    CONSTRAINT ck_problem_key CHECK (((key IS NULL) OR ((key)::text ~ '^[a-z0-9][a-z0-9-]{0,63}$'::text))),
    CONSTRAINT ck_problem_position CHECK (("position" >= 0)),
    CONSTRAINT ck_problem_severity CHECK (((severity)::text = ANY ((ARRAY['CRITICAL'::character varying, 'MAJOR'::character varying, 'MINOR'::character varying, 'TRIVIAL'::character varying])::text[])))
);

CREATE TABLE public.product_feedback (
    id uuid NOT NULL,
    account_id bigint NOT NULL,
    workspace_id bigint,
    kind character varying(16) NOT NULL,
    message character varying(5000) NOT NULL,
    page_path character varying(500),
    created_at timestamp with time zone NOT NULL,
    submission_minute timestamp with time zone NOT NULL,
    app_version character varying(64),
    user_agent character varying(500),
    resolved_at timestamp(6) with time zone,
    resolved_by_account_id bigint
);

CREATE TABLE public.pull_request_closing_issue (
    pull_request_id bigint NOT NULL,
    issue_id bigint NOT NULL
);

CREATE TABLE public.pull_request_requested_reviewers (
    pull_request_id bigint NOT NULL,
    user_id bigint NOT NULL,
    review_state character varying(32)
);

CREATE TABLE public.pull_request_requested_team (
    pull_request_id bigint NOT NULL,
    team_id bigint NOT NULL
);

CREATE TABLE public.pull_request_review (
    id bigint NOT NULL,
    commit_id character varying(255),
    html_url character varying(255),
    is_dismissed boolean NOT NULL,
    state character varying(255),
    submitted_at timestamp with time zone,
    author_id bigint,
    pull_request_id bigint,
    body text,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    author_can_push_to_repository boolean,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

CREATE TABLE public.pull_request_review_comment (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    author_association character varying(255),
    commit_id character varying(255),
    html_url character varying(255),
    line integer NOT NULL,
    original_commit_id character varying(255),
    original_line integer NOT NULL,
    original_start_line integer,
    path character varying(255),
    start_line integer,
    author_id bigint,
    pull_request_id bigint,
    review_id bigint,
    thread_id bigint NOT NULL,
    in_reply_to_id bigint,
    body text,
    diff_hunk text,
    outdated boolean,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL,
    side character varying(16),
    start_side character varying(16)
);

ALTER TABLE public.pull_request_review_comment ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.pull_request_review_comment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.pull_request_review ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.pull_request_review_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.pull_request_review_thread (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    state character varying(20),
    pull_request_id bigint NOT NULL,
    root_comment_id bigint,
    node_id character varying(128),
    path text,
    line integer,
    start_line integer,
    side character varying(16),
    start_side character varying(16),
    outdated boolean,
    collapsed boolean,
    resolved_by_id bigint,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL,
    commit_sha character varying(64),
    original_commit_sha character varying(64),
    resolved_at timestamp(6) with time zone
);

ALTER TABLE public.pull_request_review_thread ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.pull_request_review_thread_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.release_start (
    id bigint NOT NULL,
    channel character varying(16) NOT NULL,
    commit_sha character varying(40),
    environment character varying(64) NOT NULL,
    image character varying(512),
    started_at timestamp(6) with time zone NOT NULL,
    version character varying(128) NOT NULL
);

ALTER TABLE public.release_start ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.release_start_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.repository (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    default_branch character varying(255),
    description text,
    html_url character varying(512),
    is_archived boolean NOT NULL,
    is_disabled boolean NOT NULL,
    is_private boolean NOT NULL,
    name character varying(255),
    name_with_owner character varying(150),
    pushed_at timestamp with time zone,
    visibility character varying(255),
    organization_id bigint,
    last_sync_at timestamp with time zone,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

CREATE TABLE public.repository_collaborator (
    repository_id bigint NOT NULL,
    user_id bigint NOT NULL,
    permission character varying(32) NOT NULL
);

ALTER TABLE public.repository ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.repository_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.repository_to_monitor (
    id bigint NOT NULL,
    labels_synced_at timestamp with time zone,
    milestones_synced_at timestamp with time zone,
    name_with_owner character varying(255),
    repository_synced_at timestamp with time zone,
    workspace_id bigint NOT NULL,
    backfill_last_run_at timestamp(6) with time zone,
    collaborators_synced_at timestamp(6) with time zone,
    issue_sync_cursor character varying(255),
    pull_request_sync_cursor character varying(255),
    issue_backfill_high_water_mark integer,
    issue_backfill_checkpoint integer,
    pull_request_backfill_high_water_mark integer,
    pull_request_backfill_checkpoint integer,
    issues_synced_at timestamp with time zone,
    pull_requests_synced_at timestamp with time zone,
    native_id bigint,
    historical_backfill_sync_error character varying(2048),
    recent_sync_error character varying(2048),
    generated_paths jsonb NOT NULL,
    unavailable_retry_at timestamp(6) with time zone,
    unavailable_since timestamp(6) with time zone,
    backfill_repair_provider_count integer,
    repository_visibility_confirmed_at timestamp(6) with time zone
);

ALTER TABLE public.repository_to_monitor ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.repository_to_monitor_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.research_disclosure (
    id uuid NOT NULL,
    closed_at timestamp(6) with time zone,
    closure character varying(16),
    content_sha256 character varying(64) NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_by_account_id bigint,
    cutoff_at timestamp(6) with time zone NOT NULL,
    feedback_count integer NOT NULL,
    feedback_excluded_count integer NOT NULL,
    notice_version character varying(32) NOT NULL,
    participant_count integer NOT NULL,
    problem_count integer NOT NULL,
    purpose character varying(40) NOT NULL,
    research_organization character varying(255) NOT NULL,
    result_count integer NOT NULL,
    schema_name character varying(64) NOT NULL,
    schema_version integer NOT NULL,
    use_until timestamp(6) with time zone NOT NULL,
    window_from timestamp(6) with time zone NOT NULL,
    window_to timestamp(6) with time zone NOT NULL,
    workspace_id bigint NOT NULL,
    CONSTRAINT ck_research_disclosure_closure CHECK ((((closed_at IS NULL) = (closure IS NULL)) AND ((closure IS NULL) OR ((closure)::text = ANY ((ARRAY['DESTROYED'::character varying, 'ANONYMIZED'::character varying])::text[]))))),
    CONSTRAINT ck_research_disclosure_counts CHECK (((participant_count >= 0) AND (result_count >= 0) AND (problem_count >= 0) AND (feedback_count >= 0) AND (feedback_excluded_count >= 0))),
    CONSTRAINT ck_research_disclosure_hash CHECK (((content_sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_research_disclosure_purpose CHECK (((purpose)::text = 'RESEARCH_PARTICIPATION'::text)),
    CONSTRAINT ck_research_disclosure_schema CHECK ((((schema_name)::text = 'hephaestus.research-dataset'::text) AND (schema_version > 0))),
    CONSTRAINT ck_research_disclosure_use CHECK ((use_until > created_at)),
    CONSTRAINT ck_research_disclosure_window CHECK (((window_from < window_to) AND (window_to <= (cutoff_at + '1 day'::interval))))
);

CREATE TABLE public.research_disclosure_subject (
    id uuid NOT NULL,
    account_id bigint,
    disclosure_id uuid NOT NULL,
    grant_decision_id bigint,
    participant_key character varying(32) NOT NULL,
    removal_reason character varying(24),
    removal_required_at timestamp(6) with time zone,
    removed_at timestamp(6) with time zone,
    workspace_id bigint NOT NULL,
    CONSTRAINT ck_research_disclosure_subject_grant CHECK (((grant_decision_id IS NOT NULL) OR (removed_at IS NOT NULL))),
    CONSTRAINT ck_research_disclosure_subject_key CHECK (((participant_key)::text ~ '^p_[a-z2-7]{26}$'::text)),
    CONSTRAINT ck_research_disclosure_subject_link CHECK (((account_id IS NOT NULL) OR (removal_reason IS NOT NULL))),
    CONSTRAINT ck_research_disclosure_subject_pruned CHECK (((removed_at IS NULL) OR ((account_id IS NULL) AND (grant_decision_id IS NULL)))),
    CONSTRAINT ck_research_disclosure_subject_reason CHECK ((((removal_required_at IS NULL) = (removal_reason IS NULL)) AND ((removal_reason IS NULL) OR ((removal_reason)::text = ANY ((ARRAY['CONSENT_WITHDRAWN'::character varying, 'CONSENT_SUPERSEDED'::character varying, 'QUESTION_CHANGED'::character varying, 'ACCOUNT_ERASED'::character varying])::text[]))))),
    CONSTRAINT ck_research_disclosure_subject_removed CHECK (((removed_at IS NULL) OR (removal_required_at IS NOT NULL)))
);

CREATE TABLE public.result (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    review_id uuid NOT NULL,
    practice_id bigint NOT NULL,
    practice_version_id bigint,
    item_id bigint NOT NULL,
    item_version text,
    developer_id bigint,
    outcome character varying(24) NOT NULL,
    severity character varying(16),
    summary character varying(255),
    explanation text,
    evidence jsonb,
    example jsonb,
    coverage jsonb,
    evidence_version smallint DEFAULT 1 NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    end_reason character varying(24),
    CONSTRAINT ck_result_bound_version CHECK (((evidence_version = 0) OR ((practice_version_id IS NOT NULL) AND (item_version IS NOT NULL)))),
    CONSTRAINT ck_result_coverage CHECK (((evidence_version = 0) OR ((outcome)::text <> 'MET'::text) OR (coverage IS NOT NULL))),
    CONSTRAINT ck_result_coverage_type CHECK (((coverage IS NULL) OR (jsonb_typeof(coverage) = 'object'::text))),
    CONSTRAINT ck_result_dates CHECK (((ended_at IS NULL) OR (ended_at >= recorded_at))),
    CONSTRAINT ck_result_end CHECK (((ended_at IS NULL) = (end_reason IS NULL))),
    CONSTRAINT ck_result_end_reason CHECK (((end_reason IS NULL) OR ((end_reason)::text = ANY ((ARRAY['REPLACED'::character varying, 'OUTRANKED'::character varying, 'WORK_CHANGED'::character varying, 'REPAIRED'::character varying, 'REMOVED'::character varying])::text[])))),
    CONSTRAINT ck_result_evidence_type CHECK (((evidence IS NULL) OR (jsonb_typeof(evidence) = 'object'::text))),
    CONSTRAINT ck_result_evidence_version CHECK ((evidence_version = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_result_example CHECK (((example IS NULL) OR (((outcome)::text = ANY ((ARRAY['MET'::character varying, 'NOT_MET'::character varying])::text[])) AND (jsonb_typeof(example) = 'object'::text)))),
    CONSTRAINT ck_result_inapplicability CHECK (((evidence_version = 0) OR (COALESCE((jsonb_typeof((evidence -> 'inapplicability'::text)) = 'object'::text), false) = ((outcome)::text = 'DOES_NOT_APPLY'::text)))),
    CONSTRAINT ck_result_open_questions CHECK (((evidence_version = 0) OR
CASE
    WHEN ((outcome)::text = 'COULD_NOT_DETERMINE'::text) THEN
    CASE
        WHEN (jsonb_typeof((evidence -> 'openQuestions'::text)) = 'array'::text) THEN (jsonb_array_length((evidence -> 'openQuestions'::text)) > 0)
        ELSE false
    END
    WHEN ((outcome)::text = 'NOT_MET'::text) THEN true
    ELSE
    CASE
        WHEN (jsonb_typeof((evidence -> 'openQuestions'::text)) = 'array'::text) THEN (jsonb_array_length((evidence -> 'openQuestions'::text)) = 0)
        ELSE true
    END
END)),
    CONSTRAINT ck_result_outcome CHECK (((outcome)::text = ANY ((ARRAY['MET'::character varying, 'NOT_MET'::character varying, 'DOES_NOT_APPLY'::character varying, 'COULD_NOT_DETERMINE'::character varying])::text[]))),
    CONSTRAINT ck_result_question_type CHECK (((evidence_version = 0) OR (NOT jsonb_exists(evidence, 'openQuestions'::text)) OR (jsonb_typeof((evidence -> 'openQuestions'::text)) = 'array'::text))),
    CONSTRAINT ck_result_severity CHECK (((severity IS NULL) OR ((severity)::text = ANY ((ARRAY['CRITICAL'::character varying, 'MAJOR'::character varying, 'MINOR'::character varying, 'TRIVIAL'::character varying])::text[])))),
    CONSTRAINT ck_result_summary CHECK ((((outcome)::text = 'MET'::text) OR ((summary IS NOT NULL) AND (btrim((summary)::text) <> ''::text))))
);

CREATE TABLE public.result_mark (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    result_id uuid NOT NULL,
    kind character varying(16) DEFAULT 'INCORRECT'::character varying NOT NULL,
    reason character varying(500) NOT NULL,
    created_by_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    removed_by_id bigint,
    removed_at timestamp with time zone,
    removal_reason character varying(500),
    CONSTRAINT ck_result_mark_dates CHECK (((removed_at IS NULL) OR (removed_at >= created_at))),
    CONSTRAINT ck_result_mark_kind CHECK (((kind)::text = ANY ((ARRAY['INCORRECT'::character varying, 'ACCEPTED'::character varying])::text[]))),
    CONSTRAINT ck_result_mark_reason CHECK ((btrim((reason)::text) <> ''::text)),
    CONSTRAINT ck_result_mark_removed CHECK ((((removed_at IS NULL) = (removal_reason IS NULL)) AND ((removal_reason IS NULL) OR (btrim((removal_reason)::text) <> ''::text))))
);

CREATE TABLE public.review (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    item_id bigint NOT NULL,
    kind character varying(8) DEFAULT 'ITEM'::character varying NOT NULL,
    item_version text,
    base_version text,
    origin character varying(16) NOT NULL,
    event_id uuid,
    batch_id uuid,
    requested_by_id bigint,
    status character varying(16) DEFAULT 'QUEUED'::character varying NOT NULL,
    stop_reason character varying(48),
    budget_usd numeric(18,6),
    rollout_revision bigint DEFAULT 0 NOT NULL,
    ordered_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    admitted_at timestamp with time zone,
    completed_at timestamp with time zone,
    public_review_at timestamp with time zone,
    report jsonb,
    inventory_receipt jsonb,
    request_key uuid,
    request_digest character varying(64),
    checks jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT ck_review_budget CHECK ((((budget_usd IS NULL) OR ((budget_usd >= 0.0001) AND (budget_usd <= (100000)::numeric) AND (round(budget_usd, 4) = budget_usd))) AND (((kind)::text <> 'SCAN'::text) OR (budget_usd IS NOT NULL)))),
    CONSTRAINT ck_review_checks CHECK ((jsonb_typeof(checks) = 'object'::text)),
    CONSTRAINT ck_review_completed CHECK ((((status)::text = ANY ((ARRAY['COMPLETE'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])) = (completed_at IS NOT NULL))),
    CONSTRAINT ck_review_inventory CHECK (((inventory_receipt IS NULL) OR (((kind)::text = 'SCAN'::text) AND (jsonb_typeof(inventory_receipt) = 'object'::text)))),
    CONSTRAINT ck_review_kind CHECK (((kind)::text = ANY ((ARRAY['ITEM'::character varying, 'SCAN'::character varying])::text[]))),
    CONSTRAINT ck_review_origin CHECK (((origin)::text = ANY ((ARRAY['EVENT'::character varying, 'REQUEST'::character varying, 'BACKFILL'::character varying, 'SCHEDULE'::character varying])::text[]))),
    CONSTRAINT ck_review_origin_links CHECK ((((event_id IS NULL) OR ((origin)::text = 'EVENT'::text)) AND ((batch_id IS NULL) OR ((origin)::text = ANY ((ARRAY['BACKFILL'::character varying, 'SCHEDULE'::character varying])::text[]))) AND ((requested_by_id IS NULL) OR ((origin)::text = 'REQUEST'::text)))),
    CONSTRAINT ck_review_reason CHECK ((((status)::text = ANY ((ARRAY['PARTIAL'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])) = (stop_reason IS NOT NULL))),
    CONSTRAINT ck_review_report CHECK (((report IS NULL) OR (((kind)::text = 'SCAN'::text) AND (jsonb_typeof(report) = 'object'::text)))),
    CONSTRAINT ck_review_request_digest CHECK (((request_digest IS NULL) OR ((request_digest)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_review_request_pair CHECK (((request_key IS NULL) = (request_digest IS NULL))),
    CONSTRAINT ck_review_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'COMPLETE'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_review_stop_reason CHECK (((stop_reason IS NULL) OR ((stop_reason)::text = ANY ((ARRAY['TIMED_OUT'::character varying, 'EXECUTION_FAILED'::character varying, 'PREPARATION_FAILED'::character varying, 'PROCESSING_FAILED'::character varying, 'PERSON_DATA_ERASED'::character varying, 'STALE_ROLLOUT_REVISION'::character varying, 'CANCELLED_BY_ADMIN'::character varying, 'REPLACED'::character varying, 'LEGACY_FAILURE'::character varying, 'EXECUTION_INCOMPLETE'::character varying, 'RESULT_WITHHELD'::character varying, 'READINESS_REFUSED'::character varying, 'SUSPECT_LISTING'::character varying, 'BUDGET'::character varying, 'USAGE_UNVERIFIABLE'::character varying, 'DEADLINE'::character varying, 'CANCELLED'::character varying, 'ENUMERATION_FAILED'::character varying, 'ENUMERATION_LIMIT'::character varying, 'NO_SCAN_PRACTICES'::character varying, 'MODEL_CHANGED'::character varying, 'MODEL_UNAVAILABLE'::character varying, 'ERROR'::character varying, 'LEGACY_SCOPE_UNKNOWN'::character varying, 'CONTRACT_CHANGED'::character varying, 'NO_SANDBOX_ROOM'::character varying])::text[]))))
);

CREATE TABLE public.review_batch (
    id uuid NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    workspace_id bigint NOT NULL,
    kind character varying(64) NOT NULL,
    origin character varying(16) DEFAULT 'BACKFILL'::character varying NOT NULL,
    schedule_id uuid,
    from_at timestamp with time zone,
    to_at timestamp with time zone,
    status character varying(24) DEFAULT 'AWAITING_CONFIRMATION'::character varying NOT NULL,
    pause_reason character varying(32),
    budget_usd numeric(18,6),
    estimated_items integer DEFAULT 0 NOT NULL,
    estimated_cost_usd numeric(12,4),
    cursor_item_id bigint,
    submitted_count integer DEFAULT 0 NOT NULL,
    skipped_count integer DEFAULT 0 NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    requested_by_account_id bigint,
    confirmed_by_account_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_review_batch_budget CHECK (((((kind)::text = 'scm.repository'::text) AND (budget_usd IS NOT NULL) AND ((budget_usd >= 0.0001) AND (budget_usd <= (100000)::numeric)) AND (round(budget_usd, 4) = budget_usd)) OR (((kind)::text <> 'scm.repository'::text) AND (budget_usd IS NULL)))),
    CONSTRAINT ck_review_batch_cost CHECK (((estimated_cost_usd IS NULL) OR (estimated_cost_usd >= (0)::numeric))),
    CONSTRAINT ck_review_batch_counts CHECK (((estimated_items >= 0) AND (submitted_count >= 0) AND (skipped_count >= 0) AND (failed_count >= 0))),
    CONSTRAINT ck_review_batch_kind CHECK (((kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT ck_review_batch_origin CHECK (((origin)::text = ANY ((ARRAY['BACKFILL'::character varying, 'SCHEDULE'::character varying])::text[]))),
    CONSTRAINT ck_review_batch_origin_link CHECK (((schedule_id IS NULL) OR ((origin)::text = 'SCHEDULE'::text))),
    CONSTRAINT ck_review_batch_pause CHECK ((((status)::text = 'PAUSED'::text) = (pause_reason IS NOT NULL))),
    CONSTRAINT ck_review_batch_pause_reason CHECK (((pause_reason IS NULL) OR ((pause_reason)::text = ANY ((ARRAY['BUDGET_EXHAUSTED'::character varying, 'REVIEW_MODEL_UNBOUND'::character varying, 'WORKSPACE_UNAVAILABLE'::character varying, 'PAUSED_BY_ADMIN'::character varying])::text[])))),
    CONSTRAINT ck_review_batch_status CHECK (((status)::text = ANY ((ARRAY['AWAITING_CONFIRMATION'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_review_batch_version CHECK ((version >= 0)),
    CONSTRAINT ck_review_batch_window CHECK (((((kind)::text = 'scm.repository'::text) AND (from_at IS NULL) AND (to_at IS NULL)) OR (((kind)::text <> 'scm.repository'::text) AND (from_at IS NOT NULL) AND (to_at IS NOT NULL) AND (from_at < to_at))))
);

CREATE TABLE public.review_batch_target (
    batch_id uuid NOT NULL,
    item_id bigint NOT NULL,
    workspace_id bigint NOT NULL
);

CREATE TABLE public.review_composition_basis (
    job_id uuid CONSTRAINT review_composition_basis_job_id_not_null1 NOT NULL,
    workspace_id bigint CONSTRAINT review_composition_basis_workspace_id_not_null1 NOT NULL,
    admission_attempt integer CONSTRAINT review_composition_basis_admission_attempt_not_null1 NOT NULL,
    admission_digest character varying(64) CONSTRAINT review_composition_basis_admission_digest_not_null1 NOT NULL,
    basis_digest character varying(64) CONSTRAINT review_composition_basis_basis_digest_not_null1 NOT NULL,
    basis_version integer CONSTRAINT review_composition_basis_basis_version_not_null1 NOT NULL,
    copy_id uuid CONSTRAINT review_composition_basis_copy_id_not_null1 NOT NULL,
    expires_at timestamp with time zone CONSTRAINT review_composition_basis_expires_at_not_null1 NOT NULL,
    payload jsonb CONSTRAINT review_composition_basis_payload_not_null1 NOT NULL,
    CONSTRAINT ck_review_composition_basis_shape CHECK (((admission_attempt >= 0) AND (basis_version > 0) AND (jsonb_typeof(payload) = 'object'::text)))
);

CREATE TABLE public.review_item (
    workspace_id bigint NOT NULL,
    review_id uuid NOT NULL,
    practice_id bigint NOT NULL,
    item_id bigint NOT NULL,
    item_version text,
    state character varying(16) DEFAULT 'LISTED'::character varying NOT NULL,
    result_id uuid,
    job_id uuid,
    attempted_at timestamp with time zone,
    reason character varying(128),
    detail jsonb,
    CONSTRAINT ck_review_item_detail CHECK (((detail IS NULL) OR ((jsonb_typeof(detail) = 'object'::text) AND (octet_length((detail)::text) <= 8192)))),
    CONSTRAINT ck_review_item_reason CHECK (((((state)::text = 'WITHHELD'::text) = (reason IS NOT NULL)) AND ((reason IS NULL) OR (btrim((reason)::text) <> ''::text)))),
    CONSTRAINT ck_review_item_result CHECK ((((state)::text = ANY ((ARRAY['JUDGED'::character varying, 'REUSED'::character varying])::text[])) = (result_id IS NOT NULL))),
    CONSTRAINT ck_review_item_state CHECK (((state)::text = ANY ((ARRAY['LISTED'::character varying, 'JUDGED'::character varying, 'REUSED'::character varying, 'WITHHELD'::character varying])::text[]))),
    CONSTRAINT ck_review_item_version CHECK (((item_version IS NULL) OR ((btrim(item_version) <> ''::text) AND (length(item_version) <= 1024))))
);

CREATE TABLE public.review_job (
    id uuid NOT NULL,
    review_id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    submitted_at timestamp with time zone NOT NULL,
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    attempt integer DEFAULT 0 NOT NULL,
    processing_status character varying(16) DEFAULT 'NONE'::character varying NOT NULL,
    available_at timestamp with time zone DEFAULT now() NOT NULL,
    hold_reason character varying(32),
    trace_id character varying(32) NOT NULL,
    input jsonb NOT NULL,
    config_snapshot jsonb NOT NULL,
    capture jsonb,
    output jsonb,
    exit_code integer,
    error_message text,
    cancellation_reason character varying(32),
    conversation_prepared_at timestamp with time zone,
    practice_page_prepared_at timestamp with time zone,
    public_review_digest character varying(64),
    admission_attempt integer,
    admission_digest character varying(64),
    llm_calls integer DEFAULT 0 NOT NULL,
    llm_input_tokens bigint DEFAULT 0 NOT NULL,
    llm_output_tokens bigint DEFAULT 0 NOT NULL,
    llm_reasoning_tokens bigint DEFAULT 0 NOT NULL,
    llm_cache_read_tokens bigint DEFAULT 0 NOT NULL,
    llm_cache_write_tokens bigint DEFAULT 0 NOT NULL,
    started_at timestamp with time zone,
    execution_started_at timestamp with time zone,
    completed_at timestamp with time zone,
    drain_count integer DEFAULT 0 NOT NULL,
    room_refusals integer DEFAULT 0 NOT NULL,
    leased_until timestamp(6) with time zone,
    lease_id uuid,
    CONSTRAINT ck_review_job_admission CHECK (((admission_attempt IS NULL) = (admission_digest IS NULL))),
    CONSTRAINT ck_review_job_attempt CHECK ((attempt >= 0)),
    CONSTRAINT ck_review_job_cancellation CHECK (((cancellation_reason IS NULL) OR ((cancellation_reason)::text = ANY ((ARRAY['DRAIN_GRACEFUL'::character varying, 'DRAIN_IMMEDIATE'::character varying, 'BUDGET_EXHAUSTED'::character varying, 'MODEL_UNAVAILABLE'::character varying, 'MEMBER_AI_DECLINED'::character varying, 'PERSON_DATA_ERASED'::character varying, 'CANCELLED_BY_ADMIN'::character varying, 'CANCELLED_BY_REQUESTER'::character varying, 'CONTRACT_CHANGED'::character varying, 'NO_SANDBOX_ROOM'::character varying])::text[])))),
    CONSTRAINT ck_review_job_config CHECK ((jsonb_typeof(config_snapshot) = 'object'::text)),
    CONSTRAINT ck_review_job_input CHECK ((jsonb_typeof(input) = 'object'::text)),
    CONSTRAINT ck_review_job_processing CHECK (((processing_status)::text = ANY ((ARRAY['NONE'::character varying, 'PENDING'::character varying, 'DONE'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT ck_review_job_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'TIMED_OUT'::character varying, 'CANCELLED'::character varying])::text[])))
);

CREATE TABLE public.review_job_attempt_charge (
    attempt integer NOT NULL,
    job_id uuid NOT NULL,
    model_kind character varying(16) NOT NULL,
    applied_per_1m_cache_read_usd numeric(18,8),
    applied_per_1m_cache_write_usd numeric(18,8),
    applied_per_1m_input_usd numeric(18,8),
    applied_per_1m_output_usd numeric(18,8),
    applied_price_id bigint,
    applied_workspace_model_id bigint,
    funding_source character varying(16) NOT NULL,
    model character varying(128),
    pricing_state character varying(16) NOT NULL,
    workspace_id bigint NOT NULL
);

CREATE TABLE public.review_job_attempt_usage (
    attempt integer NOT NULL,
    job_id uuid NOT NULL,
    occurred_at timestamp(6) with time zone,
    proxy_cache_read_tokens bigint DEFAULT 0 NOT NULL,
    proxy_cache_write_tokens bigint DEFAULT 0 NOT NULL,
    proxy_calls integer DEFAULT 0 NOT NULL,
    proxy_input_tokens bigint DEFAULT 0 NOT NULL,
    proxy_output_tokens bigint DEFAULT 0 NOT NULL,
    proxy_reasoning_tokens bigint DEFAULT 0 NOT NULL,
    runner_cache_read_tokens integer,
    runner_cache_write_tokens integer,
    runner_calls integer,
    runner_input_tokens integer,
    runner_output_tokens integer,
    runner_reasoning_tokens integer,
    workspace_id bigint NOT NULL
);

CREATE TABLE public.review_job_script_run (
    job_id uuid NOT NULL,
    attempt integer NOT NULL,
    practice_id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    practice_version_id bigint NOT NULL,
    status character varying(16) NOT NULL,
    script_kind character varying(16) NOT NULL,
    stop_reason character varying(32),
    calls jsonb,
    models jsonb,
    finished_at timestamp with time zone NOT NULL,
    error character varying(500),
    duration_ms integer,
    CONSTRAINT ck_review_job_script_run_calls CHECK (((calls IS NULL) OR (jsonb_typeof(calls) = 'object'::text))),
    CONSTRAINT ck_review_job_script_run_counts CHECK (((attempt >= 0) AND ((duration_ms IS NULL) OR (duration_ms >= 0)))),
    CONSTRAINT ck_review_job_script_run_kind CHECK (((script_kind)::text = ANY ((ARRAY['BUILTIN'::character varying, 'CUSTOM'::character varying])::text[]))),
    CONSTRAINT ck_review_job_script_run_models CHECK (((models IS NULL) OR (jsonb_typeof(models) = 'array'::text))),
    CONSTRAINT ck_review_job_script_run_reason CHECK (((stop_reason IS NULL) OR ((stop_reason)::text = ANY ((ARRAY['UNAVAILABLE'::character varying, 'BUDGET'::character varying, 'DEADLINE'::character varying, 'TOO_LARGE'::character varying, 'OFF_FORMAT'::character varying, 'REFUSED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'INVALID_SCRIPT'::character varying, 'INVALID_CANDIDATE'::character varying])::text[])))),
    CONSTRAINT ck_review_job_script_run_status CHECK (((status)::text = ANY ((ARRAY['OK'::character varying, 'WITHHELD'::character varying, 'FAILED'::character varying, 'NOT_REACHED'::character varying, 'NOT_FINISHED'::character varying])::text[])))
);

CREATE TABLE public.review_job_script_usage (
    job_id uuid NOT NULL,
    attempt integer NOT NULL,
    model_kind character varying(16) NOT NULL,
    practice_id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    data_handling_tier character varying(24),
    calls integer DEFAULT 0 NOT NULL,
    reported_calls integer DEFAULT 0 NOT NULL,
    input_tokens bigint DEFAULT 0 NOT NULL,
    output_tokens bigint DEFAULT 0 NOT NULL,
    cache_read_tokens bigint DEFAULT 0 NOT NULL,
    cache_write_tokens bigint DEFAULT 0 NOT NULL,
    search_units bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_review_job_script_usage_counts CHECK (((attempt >= 0) AND (calls >= 0) AND (reported_calls >= 0) AND (reported_calls <= calls) AND (input_tokens >= 0) AND (output_tokens >= 0) AND (cache_read_tokens >= 0) AND (cache_write_tokens >= 0) AND (search_units >= 0))),
    CONSTRAINT ck_review_job_script_usage_kind CHECK (((model_kind)::text = ANY ((ARRAY['CHAT'::character varying, 'DECISION'::character varying, 'EMBEDDING'::character varying, 'RERANKING'::character varying])::text[]))),
    CONSTRAINT ck_review_job_script_usage_tier CHECK (((data_handling_tier IS NULL) OR ((data_handling_tier)::text = ANY ((ARRAY['IN_HOUSE'::character varying, 'CLOUD'::character varying, 'UNDECLARED'::character varying])::text[]))))
);

CREATE TABLE public.review_practice (
    review_id uuid NOT NULL,
    practice_id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    practice_version_id bigint,
    status character varying(16) DEFAULT 'QUEUED'::character varying NOT NULL,
    reason character varying(48),
    items_listed integer,
    listing_complete boolean,
    capture_decision jsonb,
    method jsonb,
    CONSTRAINT ck_review_practice_counts CHECK (((items_listed IS NULL) OR (items_listed >= 0))),
    CONSTRAINT ck_review_practice_decision CHECK (((capture_decision IS NULL) OR (jsonb_typeof(capture_decision) = 'object'::text))),
    CONSTRAINT ck_review_practice_method CHECK (((method IS NULL) OR ((jsonb_typeof(method) = 'object'::text) AND (jsonb_typeof((method -> 'models'::text)) = 'array'::text) AND
CASE
    WHEN (jsonb_typeof((method -> 'models'::text)) = 'array'::text) THEN COALESCE(((method ->> 'modelDigest'::text) = (public.review_model_digest((method -> 'models'::text)))::text), false)
    ELSE false
END AND ((((method - 'epoch'::text) - 'modelDigest'::text) - 'models'::text) = '{}'::jsonb) AND
CASE
    WHEN (jsonb_typeof((method -> 'epoch'::text)) = 'number'::text) THEN ((((method ->> 'epoch'::text))::numeric >= (1)::numeric) AND (((method ->> 'epoch'::text))::numeric = trunc(((method ->> 'epoch'::text))::numeric)))
    ELSE false
END))),
    CONSTRAINT ck_review_practice_reason CHECK ((((status)::text <> ALL ((ARRAY['WITHHELD'::character varying, 'FAILED'::character varying])::text[])) OR (reason IS NOT NULL))),
    CONSTRAINT ck_review_practice_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'DONE'::character varying, 'WITHHELD'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE public.review_schedule (
    id uuid NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    workspace_id bigint NOT NULL,
    kind character varying(64) NOT NULL,
    cadence character varying(16) DEFAULT 'DAILY'::character varying NOT NULL,
    lookback_days integer,
    budget_usd numeric(18,6),
    enabled boolean DEFAULT true NOT NULL,
    next_run_at timestamp with time zone NOT NULL,
    last_run_at timestamp with time zone,
    created_by_account_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_review_schedule_budget CHECK (((((kind)::text = 'scm.repository'::text) AND (budget_usd IS NOT NULL) AND ((budget_usd >= 0.0001) AND (budget_usd <= (100000)::numeric)) AND (round(budget_usd, 4) = budget_usd)) OR (((kind)::text <> 'scm.repository'::text) AND (budget_usd IS NULL)))),
    CONSTRAINT ck_review_schedule_cadence CHECK (((cadence)::text = ANY ((ARRAY['DAILY'::character varying, 'WEEKLY'::character varying])::text[]))),
    CONSTRAINT ck_review_schedule_kind CHECK (((kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT ck_review_schedule_lookback CHECK (((((kind)::text = 'scm.repository'::text) AND (lookback_days IS NULL)) OR (((kind)::text <> 'scm.repository'::text) AND (lookback_days IS NOT NULL) AND ((lookback_days >= 1) AND (lookback_days <=
CASE cadence
    WHEN 'DAILY'::text THEN 2
    WHEN 'WEEKLY'::text THEN 7
    ELSE NULL::integer
END))))),
    CONSTRAINT ck_review_schedule_version CHECK ((version >= 0))
);

CREATE TABLE public.review_scope_repository (
    workspace_id bigint NOT NULL,
    repository_monitor_id bigint NOT NULL,
    base_branches jsonb DEFAULT '[]'::jsonb NOT NULL,
    CONSTRAINT ck_review_scope_repository_branches CHECK (((jsonb_typeof(base_branches) = 'array'::text) AND (NOT jsonb_path_exists(base_branches, '$[*]?((@.type() != "string" || @ like_regex "^[[:space:]]*$") || @ like_regex "^.{255}." flag "s")'::jsonpath))))
);

CREATE TABLE public.shedlock (
    name character varying(64) NOT NULL,
    lock_until timestamp without time zone NOT NULL,
    locked_at timestamp without time zone NOT NULL,
    locked_by character varying(255) NOT NULL
);

CREATE TABLE public.slack_channel_consent_event (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slack_channel_id character varying(32) NOT NULL,
    from_state character varying(16),
    to_state character varying(16) NOT NULL,
    actor_account_id bigint,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_slack_channel_consent_event_from_state CHECK (((from_state IS NULL) OR ((from_state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('REVOKED'::character varying)::text])))),
    CONSTRAINT chk_slack_channel_consent_event_to_state CHECK (((to_state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('REVOKED'::character varying)::text])))
);

ALTER TABLE public.slack_channel_consent_event ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.slack_channel_consent_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.slack_message (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slack_team_id character varying(32) NOT NULL,
    slack_channel_id character varying(32) NOT NULL,
    slack_ts character varying(32) NOT NULL,
    slack_thread_ts character varying(32),
    author_slack_user_id character varying(32),
    text text,
    edited_at timestamp with time zone,
    deleted_at timestamp with time zone,
    ingested_at timestamp with time zone DEFAULT now() NOT NULL,
    author_account_id bigint
);

ALTER TABLE public.slack_message ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.slack_message_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.slack_monitored_channel (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slack_team_id character varying(32) NOT NULL,
    slack_channel_id character varying(32) NOT NULL,
    channel_name character varying(256),
    consent_state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    consent_announced_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_history_synced_ts character varying(32),
    history_synced_at timestamp with time zone,
    last_sync_error character varying(2048),
    CONSTRAINT chk_slack_monitored_channel_consent CHECK (((consent_state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('REVOKED'::character varying)::text])))
);

ALTER TABLE public.slack_monitored_channel ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.slack_monitored_channel_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.slack_participant_consent (
    workspace_id bigint NOT NULL,
    slack_user_id character varying(32) NOT NULL,
    ingestion_opted_out boolean DEFAULT false NOT NULL,
    decided_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.slack_thread (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slack_channel_id character varying(32) NOT NULL,
    slack_thread_ts character varying(32) NOT NULL,
    first_ts character varying(32),
    last_ts character varying(32),
    message_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    participant_account_ids bigint[] DEFAULT '{}'::bigint[] CONSTRAINT slack_thread_participant_member_ids_not_null NOT NULL,
    last_reviewed_ts character varying(32)
);

ALTER TABLE public.slack_thread ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.slack_thread_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.sync_job (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    connection_id bigint NOT NULL,
    kind character varying(48) NOT NULL,
    type character varying(32) NOT NULL,
    trigger_source character varying(32) NOT NULL,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    cancel_requested boolean DEFAULT false NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    heartbeat_at timestamp with time zone,
    items_processed integer,
    items_total integer,
    progress jsonb,
    error_summary text,
    triggered_by_account_id bigint,
    CONSTRAINT ck_sync_job_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('SUCCEEDED_WITH_WARNINGS'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT ck_sync_job_trigger_source CHECK (((trigger_source)::text = ANY (ARRAY[('SCHEDULED'::character varying)::text, ('MANUAL'::character varying)::text, ('LIFECYCLE'::character varying)::text, ('SYSTEM'::character varying)::text]))),
    CONSTRAINT ck_sync_job_type CHECK (((type)::text = ANY (ARRAY[('INITIAL'::character varying)::text, ('RECONCILIATION'::character varying)::text, ('BACKFILL'::character varying)::text])))
);

ALTER TABLE public.sync_job ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.sync_job_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.team (
    id bigint NOT NULL,
    name character varying(255),
    created_at timestamp(6) with time zone,
    description text,
    html_url character varying(512),
    last_sync_at timestamp with time zone,
    organization character varying(255),
    parent_id bigint,
    privacy character varying(32),
    updated_at timestamp(6) with time zone,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL,
    slug character varying(512)
);

ALTER TABLE public.team ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.team_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.team_membership (
    role character varying(32),
    user_id bigint NOT NULL,
    team_id bigint NOT NULL
);

CREATE TABLE public.team_repository_permission (
    permission character varying(32) NOT NULL,
    repository_id bigint NOT NULL,
    team_id bigint NOT NULL
);

CREATE TABLE public."user" (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    avatar_url character varying(255),
    email character varying(255),
    html_url character varying(255),
    login character varying(255),
    name character varying(255),
    type character varying(255),
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

ALTER TABLE public."user" ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.worker_registry (
    worker_id character varying(255) NOT NULL,
    last_heartbeat timestamp with time zone NOT NULL,
    registered_at timestamp with time zone NOT NULL
)
WITH (autovacuum_vacuum_scale_factor='0', autovacuum_vacuum_threshold='100');

CREATE TABLE public.workspace (
    id bigint NOT NULL,
    users_synced_at timestamp with time zone,
    account_login character varying(120) NOT NULL,
    repository_selection character varying(255),
    organization_id bigint,
    account_type character varying(10) NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    display_name character varying(120) NOT NULL,
    slug character varying(64) NOT NULL,
    status character varying(20) NOT NULL,
    updated_at timestamp(6) with time zone,
    members_synced_at timestamp(6) with time zone,
    teams_synced_at timestamp(6) with time zone,
    sub_issues_synced_at timestamp with time zone,
    issue_types_synced_at timestamp with time zone,
    issue_dependencies_synced_at timestamp with time zone,
    practices_enabled boolean DEFAULT false NOT NULL,
    practice_review_auto_trigger_enabled boolean DEFAULT true NOT NULL,
    practice_review_manual_trigger_enabled boolean DEFAULT true NOT NULL,
    practice_deliver_to_merged boolean,
    practice_cooldown_minutes integer,
    monthly_llm_budget_usd numeric(10,2),
    monthly_byo_llm_budget_usd numeric(10,2),
    practice_default_feedback_mode character varying(16),
    review_scope_repositories character varying(24) DEFAULT 'ALL'::character varying CONSTRAINT workspace_practice_repository_coverage_mode_not_null NOT NULL,
    review_scope_people character varying(24) DEFAULT 'ALL'::character varying CONSTRAINT workspace_practice_person_coverage_mode_not_null NOT NULL,
    practice_delivery_status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    practice_rollout_revision bigint DEFAULT 0 NOT NULL,
    practice_config_version bigint DEFAULT 0 NOT NULL,
    public_activity_enabled boolean DEFAULT false NOT NULL,
    public_activity_search_engines boolean DEFAULT false NOT NULL,
    practice_catalog_installed_at timestamp with time zone,
    admin_passkey_required boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_workspace_practice_delivery_status CHECK (((practice_delivery_status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text]))),
    CONSTRAINT chk_workspace_slug_assignable CHECK (public.workspace_slug_assignable((slug)::text)),
    CONSTRAINT ck_workspace_feedback_mode CHECK (((practice_default_feedback_mode IS NULL) OR ((practice_default_feedback_mode)::text = ANY ((ARRAY['OFF'::character varying, 'APPROVE_FIRST'::character varying, 'AUTOMATIC'::character varying])::text[])))),
    CONSTRAINT ck_workspace_scope_people CHECK (((review_scope_people)::text = ANY ((ARRAY['ALL'::character varying, 'SELECTED'::character varying])::text[]))),
    CONSTRAINT ck_workspace_scope_repositories CHECK (((review_scope_repositories)::text = ANY ((ARRAY['ALL'::character varying, 'SELECTED'::character varying])::text[])))
);

CREATE TABLE public.workspace_agent_binding (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    purpose character varying(32) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    timeout_seconds integer DEFAULT 10800 NOT NULL,
    max_concurrent_jobs integer DEFAULT 3 NOT NULL,
    instance_model_id bigint,
    workspace_model_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    data_handling_tier character varying(24) DEFAULT 'UNDECLARED'::character varying NOT NULL,
    CONSTRAINT ck_agent_binding_data_handling_tier CHECK (((data_handling_tier)::text = ANY ((ARRAY['IN_HOUSE'::character varying, 'CLOUD'::character varying, 'UNDECLARED'::character varying])::text[]))),
    CONSTRAINT ck_workspace_agent_binding_single_model CHECK ((num_nonnulls(instance_model_id, workspace_model_id) = 1))
);

ALTER TABLE public.workspace_agent_binding ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.workspace_agent_binding_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.workspace ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.workspace_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.workspace_llm_connection (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slug character varying(64) NOT NULL,
    display_name character varying(128) NOT NULL,
    base_url character varying(2048) NOT NULL,
    api_protocol character varying(40) NOT NULL,
    auth_mode character varying(16) DEFAULT 'BEARER'::character varying NOT NULL,
    api_key text,
    enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone,
    connection_platform character varying(32),
    CONSTRAINT ck_ws_llm_connection_api_protocol CHECK (((api_protocol)::text = ANY ((ARRAY['openai-completions'::character varying, 'openai-responses'::character varying, 'anthropic-messages'::character varying])::text[]))),
    CONSTRAINT ck_ws_llm_connection_auth_mode CHECK (((auth_mode)::text = ANY (ARRAY[('BEARER'::character varying)::text, ('API_KEY'::character varying)::text])))
);

ALTER TABLE public.workspace_llm_connection ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.workspace_llm_connection_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.workspace_llm_model (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    connection_id bigint NOT NULL,
    slug character varying(64) NOT NULL,
    display_name character varying(128) NOT NULL,
    upstream_model_id character varying(256) NOT NULL,
    context_window integer,
    max_output_tokens integer,
    pricing_mode character varying(16) DEFAULT 'UNPRICED'::character varying NOT NULL,
    per_1m_input_usd numeric(18,8),
    per_1m_output_usd numeric(18,8),
    per_1m_cache_read_usd numeric(18,8),
    per_1m_cache_write_usd numeric(18,8),
    currency character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    price_note character varying(500),
    enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone,
    reasoning_effort character varying(16),
    data_handling_note character varying(200),
    operated_by character varying(24),
    brand character varying(16),
    call_pool_id bigint,
    forced_tool_choice boolean,
    CONSTRAINT ck_workspace_llm_model_operated_by CHECK (((operated_by)::text = ANY ((ARRAY['OWN_ORGANISATION'::character varying, 'PROVIDER'::character varying])::text[]))),
    CONSTRAINT ck_ws_llm_model_price_shape CHECK (((((pricing_mode)::text = 'PRICED'::text) AND (per_1m_input_usd IS NOT NULL) AND (per_1m_input_usd >= (0)::numeric) AND (per_1m_output_usd IS NOT NULL) AND (per_1m_output_usd >= (0)::numeric) AND ((per_1m_cache_read_usd IS NULL) OR (per_1m_cache_read_usd >= (0)::numeric)) AND ((per_1m_cache_write_usd IS NULL) OR (per_1m_cache_write_usd >= (0)::numeric)) AND (GREATEST(per_1m_input_usd, per_1m_output_usd, COALESCE(per_1m_cache_read_usd, (0)::numeric), COALESCE(per_1m_cache_write_usd, (0)::numeric)) > (0)::numeric)) OR (((pricing_mode)::text = 'NO_CHARGE'::text) AND (price_note IS NOT NULL) AND (btrim((price_note)::text) <> ''::text) AND (per_1m_input_usd IS NULL) AND (per_1m_output_usd IS NULL) AND (per_1m_cache_read_usd IS NULL) AND (per_1m_cache_write_usd IS NULL)) OR (((pricing_mode)::text = 'UNPRICED'::text) AND (per_1m_input_usd IS NULL) AND (per_1m_output_usd IS NULL) AND (per_1m_cache_read_usd IS NULL) AND (per_1m_cache_write_usd IS NULL)))),
    CONSTRAINT ck_ws_llm_model_pricing_mode CHECK (((pricing_mode)::text = ANY (ARRAY[('PRICED'::character varying)::text, ('NO_CHARGE'::character varying)::text, ('UNPRICED'::character varying)::text])))
);

ALTER TABLE public.workspace_llm_model ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.workspace_llm_model_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.workspace_onboarding_settings (
    workspace_id bigint NOT NULL,
    ai_choice_required boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    required_connection_ids jsonb NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_onboarding_required_links_array CHECK ((jsonb_typeof(required_connection_ids) = 'array'::text)),
    CONSTRAINT ck_onboarding_requires_choice CHECK (((NOT enabled) OR ai_choice_required))
);

CREATE TABLE public.workspace_slug_history (
    id bigint NOT NULL,
    changed_at timestamp(6) with time zone NOT NULL,
    new_slug character varying(64) NOT NULL,
    old_slug character varying(64) NOT NULL,
    workspace_id bigint NOT NULL
);

ALTER TABLE public.workspace_slug_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.workspace_slug_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.workspace_slug_reservation (
    slug character varying(64) NOT NULL
);

CREATE TABLE public.workspace_team_label_filter (
    workspace_id bigint NOT NULL,
    team_id bigint NOT NULL,
    label_id bigint NOT NULL
);

CREATE TABLE public.workspace_team_repository_settings (
    workspace_id bigint NOT NULL,
    team_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    hidden_from_contributions boolean CONSTRAINT workspace_team_repository_se_hidden_from_contributions_not_null NOT NULL
);

CREATE TABLE public.workspace_team_settings (
    workspace_id bigint NOT NULL,
    team_id bigint NOT NULL,
    hidden boolean NOT NULL
);

ALTER TABLE ONLY public.auth_event ALTER COLUMN id SET DEFAULT nextval('public.auth_event_id_seq'::regclass);

ALTER TABLE ONLY public.config_audit_event ALTER COLUMN id SET DEFAULT nextval('public.config_audit_event_id_seq'::regclass);

ALTER TABLE ONLY public.account_export
    ADD CONSTRAINT account_export_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.account
    ADD CONSTRAINT account_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.account_public_activity_hidden_by_default
    ADD CONSTRAINT "account_public_activity_hidden_by_defaultPK" PRIMARY KEY (account_id);

ALTER TABLE ONLY public.activity_automation
    ADD CONSTRAINT "activity_automationPK" PRIMARY KEY (user_id, workspace_id);

ALTER TABLE ONLY public.activity_event
    ADD CONSTRAINT activity_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.auth_rate_limit_bucket
    ADD CONSTRAINT auth_rate_limit_bucket_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.catalog_entry
    ADD CONSTRAINT catalog_entry_pkey PRIMARY KEY (entry_type, slug);

ALTER TABLE ONLY public.client_session
    ADD CONSTRAINT "client_sessionPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.client_sign_in_handoff
    ADD CONSTRAINT "client_sign_in_handoffPK" PRIMARY KEY (code_hash);

ALTER TABLE ONLY public.config_audit_event
    ADD CONSTRAINT config_audit_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.consumer_dead_letter
    ADD CONSTRAINT consumer_dead_letter_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.event_publication
    ADD CONSTRAINT event_publication_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_model_price
    ADD CONSTRAINT ex_llm_model_price_no_overlap EXCLUDE USING gist (model_id WITH =, tstzrange(effective_from, effective_to) WITH &&);

ALTER TABLE ONLY public.feedback_basis
    ADD CONSTRAINT feedback_basis_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback_copy
    ADD CONSTRAINT feedback_copy_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback_decision
    ADD CONSTRAINT feedback_decision_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback_response
    ADD CONSTRAINT feedback_response_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback_send
    ADD CONSTRAINT feedback_send_pkey PRIMARY KEY (feedback_id);

ALTER TABLE ONLY public.identity
    ADD CONSTRAINT identity_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.identity_restriction
    ADD CONSTRAINT identity_restriction_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.instance_settings
    ADD CONSTRAINT instance_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_assignee
    ADD CONSTRAINT issue_assignee_pkey PRIMARY KEY (issue_id, user_id);

ALTER TABLE ONLY public.issue_comment
    ADD CONSTRAINT issue_comment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_label
    ADD CONSTRAINT issue_label_pkey PRIMARY KEY (issue_id, label_id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT issue_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_type
    ADD CONSTRAINT issue_type_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issued_jwt
    ADD CONSTRAINT issued_jwt_pkey PRIMARY KEY (jti);

ALTER TABLE ONLY public.item_event
    ADD CONSTRAINT item_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.item
    ADD CONSTRAINT item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.jwt_signing_key
    ADD CONSTRAINT jwt_signing_key_pkey PRIMARY KEY (kid);

ALTER TABLE ONLY public.label
    ADD CONSTRAINT label_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_call_permit
    ADD CONSTRAINT "llm_call_permitPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_call_pool
    ADD CONSTRAINT "llm_call_poolPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.login_provider
    ADD CONSTRAINT login_provider_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.membership
    ADD CONSTRAINT membership_pkey PRIMARY KEY (account_id, workspace_id);

ALTER TABLE ONLY public.mentor_conversation
    ADD CONSTRAINT "mentor_conversationPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.mentor_entry
    ADD CONSTRAINT "mentor_entryPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.mentor_turn
    ADD CONSTRAINT "mentor_turnPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.mentor_turn_attempt_usage
    ADD CONSTRAINT "mentor_turn_attempt_usagePK" PRIMARY KEY (attempt, turn_id);

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT milestone_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.notification_subscription
    ADD CONSTRAINT "notification_subscriptionPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT "organizationPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.organization_membership
    ADD CONSTRAINT "organization_membershipPK" PRIMARY KEY (organization_id, user_id);

ALTER TABLE ONLY public.outline_collection
    ADD CONSTRAINT outline_collection_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.outline_document
    ADD CONSTRAINT outline_document_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.passkey_challenge
    ADD CONSTRAINT "passkey_challengePK" PRIMARY KEY (id);

ALTER TABLE ONLY public.passkey_credential
    ADD CONSTRAINT "passkey_credentialPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.passkey_recovery_code
    ADD CONSTRAINT "passkey_recovery_codePK" PRIMARY KEY (hash);

ALTER TABLE ONLY public.person_data_request
    ADD CONSTRAINT "person_data_requestPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.person_evidence_copy
    ADD CONSTRAINT "person_evidence_copyPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.account_feature
    ADD CONSTRAINT pk_account_feature PRIMARY KEY (account_id, flag);

ALTER TABLE ONLY public.auth_event
    ADD CONSTRAINT pk_auth_event PRIMARY KEY (id, occurred_at);

ALTER TABLE ONLY public.commit_contributor
    ADD CONSTRAINT pk_commit_contributor PRIMARY KEY (id);

ALTER TABLE ONLY public.commit_file_change
    ADD CONSTRAINT pk_commit_file_change PRIMARY KEY (id);

ALTER TABLE ONLY public.commit_pull_request
    ADD CONSTRAINT pk_commit_pull_request PRIMARY KEY (commit_id, pull_request_id);

ALTER TABLE ONLY public.connection
    ADD CONSTRAINT pk_connection PRIMARY KEY (id);

ALTER TABLE ONLY public.connection_activity
    ADD CONSTRAINT pk_connection_activity PRIMARY KEY (connection_id);

ALTER TABLE ONLY public.connection_audit
    ADD CONSTRAINT pk_connection_audit PRIMARY KEY (id);

ALTER TABLE ONLY public.consent_decision
    ADD CONSTRAINT pk_consent_decision PRIMARY KEY (id);

ALTER TABLE ONLY public.fx_rate
    ADD CONSTRAINT pk_fx_rate PRIMARY KEY (id);

ALTER TABLE ONLY public.git_commit
    ADD CONSTRAINT pk_git_commit PRIMARY KEY (id);

ALTER TABLE ONLY public.identity_provider
    ADD CONSTRAINT pk_identity_provider PRIMARY KEY (id);

ALTER TABLE ONLY public.instance_llm_settings
    ADD CONSTRAINT pk_instance_llm_settings PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_blocking
    ADD CONSTRAINT pk_issue_blocking PRIMARY KEY (blocked_issue_id, blocking_issue_id);

ALTER TABLE ONLY public.llm_connection
    ADD CONSTRAINT pk_llm_connection PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_model
    ADD CONSTRAINT pk_llm_model PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_model_price
    ADD CONSTRAINT pk_llm_model_price PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_model_workspace_grant
    ADD CONSTRAINT pk_llm_model_workspace_grant PRIMARY KEY (model_id, workspace_id);

ALTER TABLE ONLY public.llm_usage_event
    ADD CONSTRAINT pk_llm_usage_event PRIMARY KEY (id);

ALTER TABLE ONLY public.oauth_state_nonce
    ADD CONSTRAINT pk_oauth_state_nonce PRIMARY KEY (nonce);

ALTER TABLE ONLY public.repository_collaborator
    ADD CONSTRAINT pk_repository_collaborator PRIMARY KEY (repository_id, user_id);

ALTER TABLE ONLY public.shedlock
    ADD CONSTRAINT pk_shedlock PRIMARY KEY (name);

ALTER TABLE ONLY public.slack_participant_consent
    ADD CONSTRAINT pk_slack_participant_consent PRIMARY KEY (slack_user_id, workspace_id);

ALTER TABLE ONLY public.sync_job
    ADD CONSTRAINT pk_sync_job PRIMARY KEY (id);

ALTER TABLE ONLY public.worker_registry
    ADD CONSTRAINT pk_worker_registry PRIMARY KEY (worker_id);

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT pk_workspace_agent_binding PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_llm_connection
    ADD CONSTRAINT pk_workspace_llm_connection PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT pk_workspace_llm_model PRIMARY KEY (id);

ALTER TABLE ONLY public.practice_group
    ADD CONSTRAINT practice_group_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT practice_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.practice_version
    ADD CONSTRAINT practice_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.problem
    ADD CONSTRAINT problem_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.product_feedback
    ADD CONSTRAINT product_feedback_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.pull_request_closing_issue
    ADD CONSTRAINT "pull_request_closing_issuePK" PRIMARY KEY (pull_request_id, issue_id);

ALTER TABLE ONLY public.pull_request_requested_reviewers
    ADD CONSTRAINT pull_request_requested_reviewers_pkey PRIMARY KEY (pull_request_id, user_id);

ALTER TABLE ONLY public.pull_request_requested_team
    ADD CONSTRAINT "pull_request_requested_teamPK" PRIMARY KEY (pull_request_id, team_id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT pull_request_review_comment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.pull_request_review
    ADD CONSTRAINT pull_request_review_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT pull_request_review_thread_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.release_start
    ADD CONSTRAINT "release_startPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.repository
    ADD CONSTRAINT repository_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.repository_to_monitor
    ADD CONSTRAINT "repository_to_monitorPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.research_disclosure
    ADD CONSTRAINT "research_disclosurePK" PRIMARY KEY (id);

ALTER TABLE ONLY public.research_disclosure_subject
    ADD CONSTRAINT "research_disclosure_subjectPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.result_mark
    ADD CONSTRAINT result_mark_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.result
    ADD CONSTRAINT result_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.review_batch
    ADD CONSTRAINT review_batch_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.review_batch_target
    ADD CONSTRAINT review_batch_target_pkey PRIMARY KEY (batch_id, item_id);

ALTER TABLE ONLY public.review_composition_basis
    ADD CONSTRAINT review_composition_basis_pkey PRIMARY KEY (job_id);

ALTER TABLE ONLY public.review_item
    ADD CONSTRAINT review_item_pkey PRIMARY KEY (review_id, practice_id, item_id);

ALTER TABLE ONLY public.review_job_attempt_charge
    ADD CONSTRAINT "review_job_attempt_chargePK" PRIMARY KEY (attempt, job_id, model_kind);

ALTER TABLE ONLY public.review_job_attempt_usage
    ADD CONSTRAINT "review_job_attempt_usagePK" PRIMARY KEY (attempt, job_id);

ALTER TABLE ONLY public.review_job
    ADD CONSTRAINT review_job_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.review_job_script_run
    ADD CONSTRAINT review_job_script_run_pkey PRIMARY KEY (attempt, job_id, practice_id);

ALTER TABLE ONLY public.review_job_script_usage
    ADD CONSTRAINT review_job_script_usage_pkey PRIMARY KEY (attempt, job_id, model_kind, practice_id);

ALTER TABLE ONLY public.review
    ADD CONSTRAINT review_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.review_practice
    ADD CONSTRAINT review_practice_pkey PRIMARY KEY (review_id, practice_id);

ALTER TABLE ONLY public.review_schedule
    ADD CONSTRAINT review_schedule_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.review_scope_repository
    ADD CONSTRAINT review_scope_repository_pkey PRIMARY KEY (repository_monitor_id, workspace_id);

ALTER TABLE ONLY public.slack_channel_consent_event
    ADD CONSTRAINT slack_channel_consent_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.slack_message
    ADD CONSTRAINT slack_message_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.slack_monitored_channel
    ADD CONSTRAINT slack_monitored_channel_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.slack_thread
    ADD CONSTRAINT slack_thread_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.team
    ADD CONSTRAINT "teamPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.team_membership
    ADD CONSTRAINT "team_membershipPK" PRIMARY KEY (team_id, user_id);

ALTER TABLE ONLY public.team_repository_permission
    ADD CONSTRAINT "team_repository_permissionPK" PRIMARY KEY (repository_id, team_id);

ALTER TABLE ONLY public.account
    ADD CONSTRAINT uc_accountpasskey_user_handle_col UNIQUE (passkey_user_handle);

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT uc_workspaceorganization_id_col UNIQUE (organization_id);

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT uc_workspaceslug_col UNIQUE (slug);

ALTER TABLE ONLY public.activity_event
    ADD CONSTRAINT uk_activity_event_workspace_key UNIQUE (workspace_id, event_key);

ALTER TABLE ONLY public.consumer_dead_letter
    ADD CONSTRAINT uk_consumer_dead_letter_delivery UNIQUE (consumer_name, stream_name, stream_sequence);

ALTER TABLE ONLY public.feedback_basis
    ADD CONSTRAINT uk_feedback_basis_ref UNIQUE NULLS NOT DISTINCT (feedback_id, result_id, problem_id);

ALTER TABLE ONLY public.feedback_basis
    ADD CONSTRAINT uk_feedback_basis_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.feedback_copy
    ADD CONSTRAINT uk_feedback_copy_external UNIQUE (feedback_id, external_ref);

ALTER TABLE ONLY public.feedback_copy
    ADD CONSTRAINT uk_feedback_copy_position UNIQUE (feedback_id, "position");

ALTER TABLE ONLY public.feedback_copy
    ADD CONSTRAINT uk_feedback_copy_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.feedback_decision
    ADD CONSTRAINT uk_feedback_decision_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT uk_feedback_developer UNIQUE (id, developer_id, workspace_id);

ALTER TABLE ONLY public.feedback_response
    ADD CONSTRAINT uk_feedback_response_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.feedback_send
    ADD CONSTRAINT uk_feedback_send_workspace_id UNIQUE (feedback_id, workspace_id);

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT uk_feedback_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.identity_restriction
    ADD CONSTRAINT uk_identity_restriction_key UNIQUE (provider_id, provider_user_key, team_key, kind);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT uk_issue_repository_type_number UNIQUE (repository_id, issue_type, number);

ALTER TABLE ONLY public.item_event
    ADD CONSTRAINT uk_item_event_source UNIQUE (workspace_id, item_id, name, source_key);

ALTER TABLE ONLY public.item_event
    ADD CONSTRAINT uk_item_event_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.item
    ADD CONSTRAINT uk_item_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.llm_usage_event
    ADD CONSTRAINT uk_llm_usage_event_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.mentor_conversation
    ADD CONSTRAINT uk_mentor_conversation_parent_entry UNIQUE (parent_entry_id);

ALTER TABLE ONLY public.mentor_conversation
    ADD CONSTRAINT uk_mentor_conversation_tenant UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.mentor_entry
    ADD CONSTRAINT uk_mentor_entry_conversation_seq UNIQUE (conversation_id, seq);

ALTER TABLE ONLY public.mentor_entry
    ADD CONSTRAINT uk_mentor_entry_conversation_slack_ts UNIQUE (conversation_id, slack_ts);

ALTER TABLE ONLY public.mentor_entry
    ADD CONSTRAINT uk_mentor_entry_tenant UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.mentor_turn_attempt_usage
    ADD CONSTRAINT uk_mentor_turn_attempt_usage_tenant UNIQUE (turn_id, attempt, workspace_id);

ALTER TABLE ONLY public.mentor_turn
    ADD CONSTRAINT uk_mentor_turn_request UNIQUE (request_id);

ALTER TABLE ONLY public.mentor_turn
    ADD CONSTRAINT uk_mentor_turn_tenant UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT uk_milestone_number_repository UNIQUE (number, repository_id);

ALTER TABLE ONLY public.notification_subscription
    ADD CONSTRAINT uk_notification_subscription_account_kind UNIQUE (account_id, kind);

ALTER TABLE ONLY public.notification_subscription
    ADD CONSTRAINT uk_notification_subscription_token UNIQUE (unsubscribe_token);

ALTER TABLE ONLY public.outline_collection
    ADD CONSTRAINT uk_outline_collection UNIQUE (workspace_id, connection_id, collection_id);

ALTER TABLE ONLY public.outline_document
    ADD CONSTRAINT uk_outline_document UNIQUE (workspace_id, connection_id, document_id);

ALTER TABLE ONLY public.person_evidence_copy
    ADD CONSTRAINT uk_person_evidence_copy_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.practice_group
    ADD CONSTRAINT uk_practice_group_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.practice_group
    ADD CONSTRAINT uk_practice_group_workspace_slug UNIQUE (workspace_id, slug);

ALTER TABLE ONLY public.practice_version
    ADD CONSTRAINT uk_practice_version_number UNIQUE (practice_id, number);

ALTER TABLE ONLY public.practice_version
    ADD CONSTRAINT uk_practice_version_owner UNIQUE (id, practice_id, workspace_id);

ALTER TABLE ONLY public.practice_version
    ADD CONSTRAINT uk_practice_version_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT uk_practice_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT uk_practice_workspace_slug UNIQUE (workspace_id, slug);

ALTER TABLE ONLY public.problem
    ADD CONSTRAINT uk_problem_key UNIQUE (result_id, key);

ALTER TABLE ONLY public.problem
    ADD CONSTRAINT uk_problem_parent UNIQUE (id, result_id, workspace_id);

ALTER TABLE ONLY public.problem
    ADD CONSTRAINT uk_problem_position UNIQUE (result_id, "position");

ALTER TABLE ONLY public.problem
    ADD CONSTRAINT uk_problem_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.product_feedback
    ADD CONSTRAINT uk_product_feedback_rate_limit UNIQUE (account_id, submission_minute);

ALTER TABLE ONLY public.repository_to_monitor
    ADD CONSTRAINT uk_repository_to_monitor_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.repository_to_monitor
    ADD CONSTRAINT uk_repository_to_monitor_workspace_native_id UNIQUE (workspace_id, native_id);

ALTER TABLE ONLY public.research_disclosure_subject
    ADD CONSTRAINT uk_research_disclosure_subject_account UNIQUE (disclosure_id, account_id);

ALTER TABLE ONLY public.research_disclosure_subject
    ADD CONSTRAINT uk_research_disclosure_subject_key UNIQUE (disclosure_id, participant_key);

ALTER TABLE ONLY public.research_disclosure
    ADD CONSTRAINT uk_research_disclosure_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.result
    ADD CONSTRAINT uk_result_assignment UNIQUE (id, practice_id, item_id, workspace_id);

ALTER TABLE ONLY public.result
    ADD CONSTRAINT uk_result_developer UNIQUE (id, developer_id, workspace_id);

ALTER TABLE ONLY public.result_mark
    ADD CONSTRAINT uk_result_mark_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.result
    ADD CONSTRAINT uk_result_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.review_batch_target
    ADD CONSTRAINT uk_review_batch_target_tenant UNIQUE (batch_id, item_id, workspace_id);

ALTER TABLE ONLY public.review_batch
    ADD CONSTRAINT uk_review_batch_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.review_composition_basis
    ADD CONSTRAINT uk_review_composition_basis_workspace UNIQUE (job_id, workspace_id);

ALTER TABLE ONLY public.review_item
    ADD CONSTRAINT uk_review_item_workspace UNIQUE (review_id, practice_id, item_id, workspace_id);

ALTER TABLE ONLY public.review_job_attempt_usage
    ADD CONSTRAINT uk_review_job_attempt_usage_workspace UNIQUE (job_id, attempt, workspace_id);

ALTER TABLE ONLY public.review_job
    ADD CONSTRAINT uk_review_job_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.review_practice
    ADD CONSTRAINT uk_review_practice_tenant UNIQUE (review_id, practice_id, workspace_id);

ALTER TABLE ONLY public.review
    ADD CONSTRAINT uk_review_request UNIQUE (workspace_id, request_key);

ALTER TABLE ONLY public.review_schedule
    ADD CONSTRAINT uk_review_schedule_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.review_schedule
    ADD CONSTRAINT uk_review_schedule_workspace_kind UNIQUE (workspace_id, kind);

ALTER TABLE ONLY public.review
    ADD CONSTRAINT uk_review_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.slack_channel_consent_event
    ADD CONSTRAINT uk_slack_channel_consent_event_tenant UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.slack_message
    ADD CONSTRAINT uk_slack_message UNIQUE (workspace_id, slack_channel_id, slack_ts);

ALTER TABLE ONLY public.slack_message
    ADD CONSTRAINT uk_slack_message_tenant UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.slack_monitored_channel
    ADD CONSTRAINT uk_slack_monitored_channel UNIQUE (workspace_id, slack_channel_id);

ALTER TABLE ONLY public.slack_thread
    ADD CONSTRAINT uk_slack_thread UNIQUE (workspace_id, slack_channel_id, slack_thread_ts);

ALTER TABLE ONLY public.slack_thread
    ADD CONSTRAINT uk_slack_thread_tenant UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.team
    ADD CONSTRAINT uk_team_provider_organization_slug UNIQUE (provider_id, organization, slug);

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT uk_workspace_agent_binding_purpose_tier UNIQUE (workspace_id, purpose, data_handling_tier);

ALTER TABLE ONLY public.commit_contributor
    ADD CONSTRAINT uq_commit_contributor_commit_email_role UNIQUE (commit_id, email, role);

ALTER TABLE ONLY public.connection
    ADD CONSTRAINT uq_connection UNIQUE (workspace_id, kind, instance_key);

ALTER TABLE ONLY public.git_commit
    ADD CONSTRAINT uq_git_commit_sha_repository UNIQUE (sha, repository_id);

ALTER TABLE ONLY public.identity_provider
    ADD CONSTRAINT uq_identity_provider_type_server_url UNIQUE (type, server_url);

ALTER TABLE ONLY public.issue_comment
    ADD CONSTRAINT uq_issue_comment_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT uq_issue_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.issued_jwt
    ADD CONSTRAINT uq_issued_jwt_refresh_token_hash UNIQUE (refresh_token_hash);

ALTER TABLE ONLY public.label
    ADD CONSTRAINT uq_label_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.label
    ADD CONSTRAINT uq_label_repository_name UNIQUE (repository_id, name);

ALTER TABLE ONLY public.login_provider
    ADD CONSTRAINT uq_login_provider_registration_id UNIQUE (registration_id);

ALTER TABLE ONLY public.login_provider
    ADD CONSTRAINT uq_login_provider_type_base_url UNIQUE (type, base_url);

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT uq_milestone_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT uq_organization_provider_login UNIQUE (provider_id, login);

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT uq_organization_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT uq_pr_review_comment_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.pull_request_review
    ADD CONSTRAINT uq_pr_review_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT uq_pr_review_thread_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT uq_pr_review_thread_root_comment UNIQUE (root_comment_id);

ALTER TABLE ONLY public.repository
    ADD CONSTRAINT uq_repository_provider_name_with_owner UNIQUE (provider_id, name_with_owner);

ALTER TABLE ONLY public.repository
    ADD CONSTRAINT uq_repository_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.team
    ADD CONSTRAINT uq_team_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT uq_user_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.connection
    ADD CONSTRAINT ux_connection_id_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.fx_rate
    ADD CONSTRAINT ux_fx_rate_date UNIQUE (rate_date);

ALTER TABLE ONLY public.llm_call_pool
    ADD CONSTRAINT ux_llm_call_pool_slug UNIQUE (slug);

ALTER TABLE ONLY public.llm_connection
    ADD CONSTRAINT ux_llm_connection_slug UNIQUE (slug);

ALTER TABLE ONLY public.llm_model
    ADD CONSTRAINT ux_llm_model_connection_slug UNIQUE (connection_id, slug);

ALTER TABLE ONLY public.llm_model
    ADD CONSTRAINT ux_llm_model_connection_upstream UNIQUE (connection_id, upstream_model_id);

ALTER TABLE ONLY public.llm_usage_event
    ADD CONSTRAINT ux_llm_usage_event_source_attempt UNIQUE (source_type, source_id, source_attempt);

ALTER TABLE ONLY public.workspace_llm_connection
    ADD CONSTRAINT ux_ws_llm_connection_id_ws UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.workspace_llm_connection
    ADD CONSTRAINT ux_ws_llm_connection_ws_slug UNIQUE (workspace_id, slug);

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT ux_ws_llm_model_connection_upstream UNIQUE (connection_id, upstream_model_id);

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT ux_ws_llm_model_id_ws UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT ux_ws_llm_model_ws_slug UNIQUE (workspace_id, slug);

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT "workspacePK" PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_onboarding_settings
    ADD CONSTRAINT "workspace_onboarding_settingsPK" PRIMARY KEY (workspace_id);

ALTER TABLE ONLY public.workspace_slug_history
    ADD CONSTRAINT "workspace_slug_historyPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_slug_reservation
    ADD CONSTRAINT "workspace_slug_reservationPK" PRIMARY KEY (slug);

ALTER TABLE ONLY public.workspace_team_label_filter
    ADD CONSTRAINT "workspace_team_label_filterPK" PRIMARY KEY (label_id, team_id, workspace_id);

ALTER TABLE ONLY public.workspace_team_repository_settings
    ADD CONSTRAINT "workspace_team_repository_settingsPK" PRIMARY KEY (repository_id, team_id, workspace_id);

ALTER TABLE ONLY public.workspace_team_settings
    ADD CONSTRAINT "workspace_team_settingsPK" PRIMARY KEY (team_id, workspace_id);

CREATE UNIQUE INDEX "IX_workspace_team_label_filterPK" ON public.workspace_team_label_filter USING btree (label_id, team_id, workspace_id);

CREATE UNIQUE INDEX "IX_workspace_team_repository_settingsPK" ON public.workspace_team_repository_settings USING btree (repository_id, team_id, workspace_id);

CREATE UNIQUE INDEX "IX_workspace_team_settingsPK" ON public.workspace_team_settings USING btree (team_id, workspace_id);

CREATE INDEX event_publication_by_completion_date_idx ON public.event_publication USING btree (completion_date);

CREATE INDEX event_publication_serialized_event_hash_idx ON public.event_publication USING hash (serialized_event);

CREATE INDEX idx_activity_event_actor_occurred ON public.activity_event USING btree (actor_id, occurred_at DESC);

CREATE INDEX idx_activity_event_target ON public.activity_event USING btree (target_id, target_type);

CREATE INDEX idx_activity_event_type_time ON public.activity_event USING btree (event_type, occurred_at);

CREATE INDEX idx_activity_event_workspace_actor_occurred ON public.activity_event USING btree (workspace_id, actor_id, occurred_at DESC);

CREATE INDEX idx_activity_event_workspace_covering ON public.activity_event USING btree (workspace_id, occurred_at) INCLUDE (actor_id, event_type, target_type, target_id, repository_id);

CREATE INDEX idx_activity_event_workspace_target ON public.activity_event USING btree (workspace_id, target_type, target_id);

CREATE INDEX idx_commit_contributor_commit_id ON public.commit_contributor USING btree (commit_id);

CREATE INDEX idx_commit_contributor_user_id ON public.commit_contributor USING btree (user_id);

CREATE INDEX idx_commit_file_change_commit_id ON public.commit_file_change USING btree (commit_id);

CREATE INDEX idx_commit_file_change_filename ON public.commit_file_change USING btree (filename);

CREATE INDEX idx_commit_pull_request_pr_id ON public.commit_pull_request USING btree (pull_request_id);

CREATE INDEX idx_consumer_dead_letter_workspace_id ON public.consumer_dead_letter USING btree (workspace_id, id);

CREATE INDEX idx_feedback_basis_order ON public.feedback_basis USING btree (feedback_id, ordinal, id);

CREATE INDEX idx_feedback_basis_problem ON public.feedback_basis USING btree (problem_id) WHERE (problem_id IS NOT NULL);

CREATE INDEX idx_feedback_basis_result ON public.feedback_basis USING btree (result_id, workspace_id);

CREATE INDEX idx_feedback_copy_external ON public.feedback_copy USING btree (external_ref) WHERE (external_ref IS NOT NULL);

CREATE INDEX idx_feedback_copy_mentor_entry ON public.feedback_copy USING btree (mentor_entry_id) WHERE (mentor_entry_id IS NOT NULL);

CREATE INDEX idx_feedback_copy_mentor_turn ON public.feedback_copy USING btree (mentor_turn_id) WHERE (mentor_turn_id IS NOT NULL);

CREATE INDEX idx_feedback_decision_actor ON public.feedback_decision USING btree (actor_account_id) WHERE (actor_account_id IS NOT NULL);

CREATE INDEX idx_feedback_decision_latest ON public.feedback_decision USING btree (feedback_id, decided_at DESC, id DESC);

CREATE INDEX idx_feedback_item ON public.feedback USING btree (workspace_id, item_id, destination, created_at DESC) WHERE (item_id IS NOT NULL);

CREATE INDEX idx_feedback_ready ON public.feedback USING btree (workspace_id, destination, created_at, id) WHERE ((state)::text = ANY ((ARRAY['READY'::character varying, 'AWAITING_APPROVAL'::character varying])::text[]));

CREATE INDEX idx_feedback_recipient ON public.feedback USING btree (workspace_id, developer_id, destination, created_at DESC, id DESC);

CREATE INDEX idx_feedback_replaces ON public.feedback USING btree (replaces_id) WHERE (replaces_id IS NOT NULL);

CREATE INDEX idx_feedback_response_latest ON public.feedback_response USING btree (feedback_id, created_at DESC, id DESC);

CREATE INDEX idx_feedback_review ON public.feedback USING btree (workspace_id, review_id, destination, developer_id);

CREATE INDEX idx_feedback_send_due ON public.feedback_send USING btree (next_attempt_at, feedback_id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'UNCERTAIN'::character varying])::text[]));

CREATE INDEX idx_feedback_send_lease ON public.feedback_send USING btree (lease_expires_at, feedback_id) WHERE ((state)::text = 'SENDING'::text);

CREATE INDEX idx_git_commit_author_id ON public.git_commit USING btree (author_id);

CREATE INDEX idx_git_commit_authored_at ON public.git_commit USING btree (authored_at);

CREATE INDEX idx_git_commit_committer_id ON public.git_commit USING btree (committer_id);

CREATE INDEX idx_git_commit_repository_id ON public.git_commit USING btree (repository_id);

CREATE INDEX idx_git_commit_unresolved_author_email ON public.git_commit USING btree (repository_id, author_email) WHERE ((author_id IS NULL) AND (author_email IS NOT NULL));

CREATE INDEX idx_git_commit_unresolved_committer_email ON public.git_commit USING btree (repository_id, committer_email) WHERE ((committer_id IS NULL) AND (committer_email IS NOT NULL));

CREATE INDEX idx_identity_account ON public.identity USING btree (account_id);

CREATE INDEX idx_issue_author_id ON public.issue USING btree (author_id);

CREATE INDEX idx_issue_blocking_blocked_id ON public.issue_blocking USING btree (blocked_issue_id);

CREATE INDEX idx_issue_blocking_blocking_id ON public.issue_blocking USING btree (blocking_issue_id);

CREATE INDEX idx_issue_comment_author_id ON public.issue_comment USING btree (author_id);

CREATE INDEX idx_issue_comment_created_at ON public.issue_comment USING btree (created_at);

CREATE INDEX idx_issue_comment_issue ON public.issue_comment USING btree (issue_id, id);

CREATE INDEX idx_issue_issue_type_id ON public.issue USING btree (issue_type_id);

CREATE INDEX idx_issue_parent_issue_id ON public.issue USING btree (parent_issue_id);

CREATE INDEX idx_issue_repository_id ON public.issue USING btree (repository_id);

CREATE INDEX idx_issue_state ON public.issue USING btree (state);

CREATE INDEX idx_issue_type_organization_id ON public.issue_type USING btree (organization_id);

CREATE INDEX idx_item_event_evidence_expiry ON public.item_event USING btree (evidence_expires_at);

CREATE INDEX idx_item_event_queue ON public.item_event USING btree (state, retry_at, id) WHERE ((state)::text = ANY ((ARRAY['RECORDED'::character varying, 'WAITING'::character varying])::text[]));

CREATE INDEX idx_item_event_request_quota ON public.item_event USING btree (workspace_id, requested_by_id, occurred_at DESC) WHERE (requested_by_id IS NOT NULL);

CREATE INDEX idx_item_event_trace ON public.item_event USING btree (workspace_id, item_id, occurred_at DESC, id);

CREATE INDEX idx_item_parent_active ON public.item USING btree (workspace_id, parent_id, id) WHERE (removed_at IS NULL);

CREATE INDEX idx_item_provider_source ON public.item USING btree (kind, source_id) WHERE (source_id IS NOT NULL);

CREATE INDEX idx_llm_call_permit_pool ON public.llm_call_permit USING btree (pool_id);

CREATE INDEX idx_llm_model_connection ON public.llm_model USING btree (connection_id);

CREATE INDEX idx_llm_model_grant_workspace ON public.llm_model_workspace_grant USING btree (workspace_id);

CREATE INDEX idx_llm_model_price_model_from ON public.llm_model_price USING btree (model_id, effective_from);

CREATE INDEX idx_llm_usage_occurred_at ON public.llm_usage_event USING btree (occurred_at);

CREATE INDEX idx_llm_usage_review ON public.llm_usage_event USING btree (workspace_id, review_id, occurred_at) WHERE (review_id IS NOT NULL);

CREATE INDEX idx_llm_usage_ws_time ON public.llm_usage_event USING btree (workspace_id, occurred_at);

CREATE INDEX idx_membership_account ON public.membership USING btree (account_id, workspace_id);

CREATE INDEX idx_membership_review_scope ON public.membership USING btree (workspace_id, account_id) WHERE ((left_at IS NULL) AND review_selected);

CREATE INDEX idx_membership_workspace_account ON public.membership USING btree (workspace_id, account_id);

CREATE INDEX idx_mentor_conversation_parent ON public.mentor_conversation USING btree (parent_conversation_id);

CREATE INDEX idx_mentor_conversation_slack ON public.mentor_conversation USING btree (slack_user_id, slack_team_id) WHERE (slack_user_id IS NOT NULL);

CREATE INDEX idx_mentor_conversation_workspace_account ON public.mentor_conversation USING btree (workspace_id, account_id);

CREATE INDEX idx_mentor_entry_turn ON public.mentor_entry USING btree (turn_id);

CREATE INDEX idx_mentor_entry_workspace_surface ON public.mentor_entry USING btree (workspace_id, surface);

CREATE INDEX idx_mentor_turn_conversation_status ON public.mentor_turn USING btree (conversation_id, status);

CREATE INDEX idx_mentor_turn_leased_until ON public.mentor_turn USING btree (leased_until) WHERE (leased_until IS NOT NULL);

CREATE INDEX idx_mentor_turn_status_created ON public.mentor_turn USING btree (status, created_at);

CREATE INDEX idx_mentor_turn_status_queued ON public.mentor_turn USING btree (status, queued_at);

CREATE INDEX idx_mentor_turn_workspace_surface ON public.mentor_turn USING btree (workspace_id, surface);

CREATE INDEX idx_notification_subscription_delivery ON public.notification_subscription USING btree (kind, enabled, account_id);

CREATE INDEX idx_person_evidence_copy_job ON public.person_evidence_copy USING btree (job_id);

CREATE INDEX idx_person_evidence_copy_retention ON public.person_evidence_copy USING btree (state, created_at);

CREATE INDEX idx_person_evidence_copy_store ON public.person_evidence_copy USING btree (store_id, state);

CREATE INDEX idx_pr_review_author_id ON public.pull_request_review USING btree (author_id);

CREATE INDEX idx_pr_review_pull_request_id ON public.pull_request_review USING btree (pull_request_id);

CREATE INDEX idx_pr_review_submitted_at ON public.pull_request_review USING btree (submitted_at);

CREATE INDEX idx_practice_catalog_slug ON public.practice USING btree (catalog_slug) WHERE (catalog_slug IS NOT NULL);

CREATE INDEX idx_practice_group_catalog_slug ON public.practice_group USING btree (catalog_slug) WHERE (catalog_slug IS NOT NULL);

CREATE INDEX idx_practice_group_order ON public.practice USING btree (workspace_id, group_id, "position", id);

CREATE INDEX idx_practice_kind ON public.practice USING btree (workspace_id, kind);

CREATE INDEX idx_practice_version_creator ON public.practice_version USING btree (created_by_account_id) WHERE (created_by_account_id IS NOT NULL);

CREATE INDEX idx_problem_report ON public.problem USING btree (workspace_id, result_id, "position");

CREATE INDEX idx_product_feedback_created_at ON public.product_feedback USING btree (created_at);

CREATE INDEX idx_pull_request_requested_team_team ON public.pull_request_requested_team USING btree (team_id);

CREATE INDEX idx_pull_request_review_comment_thread ON public.pull_request_review_comment USING btree (thread_id);

CREATE INDEX idx_pull_request_review_thread_pull_request ON public.pull_request_review_thread USING btree (pull_request_id);

CREATE INDEX idx_pull_request_review_thread_resolved_by ON public.pull_request_review_thread USING btree (resolved_by_id);

CREATE INDEX idx_repository_name_with_owner ON public.repository USING btree (name_with_owner);

CREATE INDEX idx_repository_organization_id ON public.repository USING btree (organization_id);

CREATE INDEX idx_research_disclosure_subject_account ON public.research_disclosure_subject USING btree (account_id);

CREATE INDEX idx_research_disclosure_subject_disclosure ON public.research_disclosure_subject USING btree (workspace_id, disclosure_id);

CREATE INDEX idx_result_admin ON public.result USING btree (workspace_id, practice_id, outcome, recorded_at DESC, id);

CREATE INDEX idx_result_developer ON public.result USING btree (workspace_id, developer_id, recorded_at DESC, id DESC);

CREATE INDEX idx_result_developer_current ON public.result USING btree (workspace_id, developer_id, recorded_at DESC, id) WHERE (ended_at IS NULL);

CREATE INDEX idx_result_history ON public.result USING btree (workspace_id, practice_id, item_id, developer_id, recorded_at, id);

CREATE INDEX idx_result_item_current ON public.result USING btree (workspace_id, item_id, practice_id, id) WHERE (ended_at IS NULL);

CREATE INDEX idx_result_mark_history ON public.result_mark USING btree (workspace_id, result_id, created_at DESC);

CREATE INDEX idx_result_open_problems ON public.result USING btree (workspace_id, item_id, practice_id, severity, id) WHERE ((ended_at IS NULL) AND ((outcome)::text = 'NOT_MET'::text));

CREATE INDEX idx_result_retention ON public.result USING btree (workspace_id, ended_at, id) WHERE (ended_at IS NOT NULL);

CREATE INDEX idx_result_review ON public.result USING btree (workspace_id, review_id, practice_id, item_id);

CREATE INDEX idx_review_batch ON public.review USING btree (batch_id) WHERE (batch_id IS NOT NULL);

CREATE INDEX idx_review_batch_confirmer ON public.review_batch USING btree (confirmed_by_account_id) WHERE (confirmed_by_account_id IS NOT NULL);

CREATE INDEX idx_review_batch_cursor ON public.review_batch USING btree (cursor_item_id) WHERE (cursor_item_id IS NOT NULL);

CREATE INDEX idx_review_batch_driver ON public.review_batch USING btree (workspace_id, status, created_at, id) WHERE ((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'PAUSED'::character varying])::text[]));

CREATE INDEX idx_review_batch_requester ON public.review_batch USING btree (requested_by_account_id);

CREATE INDEX idx_review_batch_schedule ON public.review_batch USING btree (schedule_id) WHERE (schedule_id IS NOT NULL);

CREATE INDEX idx_review_batch_target_item ON public.review_batch_target USING btree (workspace_id, item_id, batch_id);

CREATE INDEX idx_review_comment_pull_request ON public.pull_request_review_comment USING btree (pull_request_id, id);

CREATE INDEX idx_review_comment_review_reply ON public.pull_request_review_comment USING btree (review_id, in_reply_to_id);

CREATE INDEX idx_review_composition_basis_expiry ON public.review_composition_basis USING btree (expires_at, job_id);

CREATE INDEX idx_review_event ON public.review USING btree (event_id) WHERE (event_id IS NOT NULL);

CREATE INDEX idx_review_item ON public.review USING btree (workspace_id, item_id, created_at DESC, id);

CREATE INDEX idx_review_item_item ON public.review_item USING btree (workspace_id, item_id, review_id);

CREATE INDEX idx_review_item_job ON public.review_item USING btree (job_id) WHERE (job_id IS NOT NULL);

CREATE INDEX idx_review_item_pending ON public.review_item USING btree (workspace_id, review_id, item_id, practice_id) WHERE ((state)::text = 'LISTED'::text);

CREATE INDEX idx_review_item_result ON public.review_item USING btree (result_id) WHERE (result_id IS NOT NULL);

CREATE INDEX idx_review_job_attempt_charge_workspace ON public.review_job_attempt_charge USING btree (workspace_id);

CREATE INDEX idx_review_job_attempt_usage_workspace ON public.review_job_attempt_usage USING btree (workspace_id);

CREATE INDEX idx_review_job_completion ON public.review_job USING btree (completed_at, id) WHERE (((status)::text <> ALL ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying])::text[])) AND ((processing_status)::text = ANY ((ARRAY['NONE'::character varying, 'PENDING'::character varying])::text[])));

CREATE INDEX idx_review_job_queue ON public.review_job USING btree (status, available_at, id) WHERE ((status)::text = 'QUEUED'::text);

CREATE INDEX idx_review_job_retention ON public.review_job USING btree (completed_at, id) WHERE (((status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying, 'TIMED_OUT'::character varying, 'CANCELLED'::character varying])::text[])) AND ((processing_status)::text = ANY ((ARRAY['NONE'::character varying, 'DONE'::character varying, 'FAILED'::character varying])::text[])));

CREATE INDEX idx_review_job_review ON public.review_job USING btree (workspace_id, review_id, id);

CREATE INDEX idx_review_job_script_run_job_attempt ON public.review_job_script_run USING btree (job_id, attempt, practice_id);

CREATE INDEX idx_review_job_script_run_practice ON public.review_job_script_run USING btree (workspace_id, practice_id, finished_at DESC);

CREATE INDEX idx_review_job_script_usage_job ON public.review_job_script_usage USING btree (job_id);

CREATE INDEX idx_review_job_script_usage_workspace ON public.review_job_script_usage USING btree (workspace_id);

CREATE INDEX idx_review_practice_practice ON public.review_practice USING btree (workspace_id, practice_id, review_id);

CREATE INDEX idx_review_schedule_creator ON public.review_schedule USING btree (created_by_account_id);

CREATE INDEX idx_review_schedule_due ON public.review_schedule USING btree (next_run_at, id) WHERE enabled;

CREATE INDEX idx_review_scope_repository_workspace ON public.review_scope_repository USING btree (workspace_id, repository_monitor_id);

CREATE INDEX idx_slack_channel_consent_event_channel ON public.slack_channel_consent_event USING btree (workspace_id, slack_channel_id, created_at);

CREATE INDEX idx_slack_message_ingest ON public.slack_message USING btree (workspace_id, ingested_at);

CREATE INDEX idx_slack_message_thread ON public.slack_message USING btree (workspace_id, slack_channel_id, slack_thread_ts);

CREATE INDEX idx_slack_thread_participants ON public.slack_thread USING gin (participant_account_ids);

CREATE INDEX idx_workspace_slug_history_old_slug ON public.workspace_slug_history USING btree (old_slug);

CREATE INDEX idx_workspace_slug_history_workspace_id ON public.workspace_slug_history USING btree (workspace_id);

CREATE INDEX idx_workspace_team_label_filter_team_id ON public.workspace_team_label_filter USING btree (team_id);

CREATE INDEX idx_workspace_team_repo_settings_team_repo ON public.workspace_team_repository_settings USING btree (team_id, repository_id);

CREATE INDEX idx_workspace_team_settings_team_id ON public.workspace_team_settings USING btree (team_id);

CREATE INDEX idx_ws_llm_model_connection ON public.workspace_llm_model USING btree (connection_id);

CREATE INDEX ix_account_export_account ON public.account_export USING btree (account_id);

CREATE INDEX ix_account_export_ready_expiry ON public.account_export USING btree (expires_at) WHERE ((status)::text = 'READY'::text);

CREATE INDEX ix_account_status_nonactive ON public.account USING btree (status) WHERE ((status)::text <> 'ACTIVE'::text);

CREATE INDEX ix_auth_event_account_occurred ON ONLY public.auth_event USING btree (account_id, occurred_at DESC) WHERE (account_id IS NOT NULL);

CREATE INDEX ix_auth_event_failure_ip ON ONLY public.auth_event USING btree (ip_inet) WHERE ((result)::text = 'FAILURE'::text);

CREATE INDEX ix_auth_event_occurred ON ONLY public.auth_event USING btree (occurred_at);

CREATE INDEX ix_auth_event_viewed_account_occurred ON ONLY public.auth_event USING btree (viewed_account_id, occurred_at) WHERE (viewed_account_id IS NOT NULL);

CREATE INDEX ix_auth_rate_limit_bucket_expires_at ON public.auth_rate_limit_bucket USING btree (expires_at);

CREATE INDEX ix_client_session_account_id ON public.client_session USING btree (account_id);

CREATE INDEX ix_client_session_session_expires_at ON public.client_session USING btree (session_expires_at);

CREATE INDEX ix_client_sign_in_handoff_account_id ON public.client_sign_in_handoff USING btree (account_id);

CREATE INDEX ix_client_sign_in_handoff_expires_at ON public.client_sign_in_handoff USING btree (expires_at);

CREATE INDEX ix_config_audit_event_actor ON public.config_audit_event USING btree (actor_account_id, occurred_at DESC) WHERE (actor_account_id IS NOT NULL);

CREATE INDEX ix_config_audit_event_changed_keys ON public.config_audit_event USING gin (changed_keys);

CREATE INDEX ix_config_audit_event_entity ON public.config_audit_event USING btree (workspace_id, entity_type, entity_id, occurred_at DESC, id DESC);

CREATE INDEX ix_config_audit_event_occurred ON public.config_audit_event USING btree (occurred_at DESC, id DESC);

CREATE INDEX ix_config_audit_event_workspace_occurred ON public.config_audit_event USING btree (workspace_id, occurred_at DESC, id DESC);

CREATE INDEX ix_connection_audit_lookup ON public.connection_audit USING btree (connection_id, occurred_at DESC);

CREATE INDEX ix_connection_credential_rotation ON public.connection USING btree (credentials_key_version, id) WHERE (credentials_encrypted IS NOT NULL);

CREATE INDEX ix_connection_outline_subscription ON public.connection USING btree (((config ->> 'webhookSubscriptionId'::text))) WHERE (((kind)::text = 'OUTLINE'::text) AND ((state)::text = 'ACTIVE'::text));

CREATE INDEX ix_connection_workspace_kind ON public.connection USING btree (workspace_id, kind);

CREATE INDEX ix_consent_decision_latest ON public.consent_decision USING btree (account_id, purpose, occurred_at DESC, id DESC);

CREATE INDEX ix_issue_repository_live ON public.issue USING btree (repository_id, issue_type) WHERE (deleted_at IS NULL);

CREATE INDEX ix_issued_jwt_account_active ON public.issued_jwt USING btree (account_id) WHERE (revoked_at IS NULL);

CREATE INDEX ix_issued_jwt_expires_at ON public.issued_jwt USING btree (expires_at);

CREATE INDEX ix_issued_jwt_predecessor_jti ON public.issued_jwt USING btree (account_id, predecessor_jti, issued_at);

CREATE INDEX ix_issued_jwt_session_id ON public.issued_jwt USING btree (session_id);

CREATE INDEX ix_jwt_signing_key_active ON public.jwt_signing_key USING btree (active) WHERE (active = true);

CREATE INDEX ix_mentor_entry_digest_unsettled ON public.mentor_entry USING btree (created_at) WHERE (((kind)::text = 'DIGEST'::text) AND ((slack_delivery)::text = ANY ((ARRAY['PENDING'::character varying, 'UNCERTAIN'::character varying])::text[])));

CREATE INDEX ix_mentor_entry_slack_question_unsettled ON public.mentor_entry USING btree (turn_id) WHERE (((kind)::text = 'USER'::text) AND ((surface)::text = 'SLACK'::text) AND ((slack_delivery IS NULL) OR ((slack_delivery)::text = ANY ((ARRAY['PENDING'::character varying, 'UNCERTAIN'::character varying])::text[]))));

CREATE INDEX ix_mentor_entry_text_search ON public.mentor_entry USING gin (to_tsvector('simple'::regconfig, search_text)) WHERE ((kind)::text = ANY ((ARRAY['USER'::character varying, 'HEPH'::character varying, 'DIGEST'::character varying])::text[]));

CREATE INDEX ix_oauth_state_nonce_issued_at ON public.oauth_state_nonce USING btree (issued_at);

CREATE INDEX ix_outline_collection_pending_sync ON public.outline_collection USING btree (workspace_id) WHERE (((state)::text = 'ENABLED'::text) AND ((sync_status)::text = 'PENDING'::text));

CREATE INDEX ix_outline_document_collection ON public.outline_document USING btree (workspace_id, collection_id);

CREATE INDEX ix_outline_document_eviction ON public.outline_document USING btree (workspace_id, last_materialized_at NULLS FIRST) WHERE (body_markdown IS NOT NULL);

CREATE INDEX ix_outline_document_fts ON public.outline_document USING gin (to_tsvector('simple'::regconfig, (((COALESCE(title, ''::character varying))::text || ' '::text) || "left"(COALESCE(body_markdown, ''::text), 900000))));

CREATE INDEX ix_outline_document_projection ON public.outline_document USING btree (workspace_id, deleted_at NULLS FIRST, outline_updated_at DESC NULLS LAST, id);

CREATE INDEX ix_outline_document_slug ON public.outline_document USING btree (workspace_id, slug);

CREATE INDEX ix_passkey_challenge_account ON public.passkey_challenge USING btree (account_id);

CREATE INDEX ix_passkey_challenge_expiry ON public.passkey_challenge USING btree (expires_at);

CREATE INDEX ix_passkey_credential_account ON public.passkey_credential USING btree (account_id);

CREATE INDEX ix_passkey_recovery_code_account ON public.passkey_recovery_code USING btree (account_id);

CREATE INDEX ix_review_job_running_lease ON public.review_job USING btree (leased_until) WHERE ((status)::text = 'RUNNING'::text);

CREATE INDEX ix_sync_job_connection_created ON public.sync_job USING btree (connection_id, created_at DESC);

CREATE INDEX ix_worker_registry_last_heartbeat ON public.worker_registry USING btree (last_heartbeat);

CREATE UNIQUE INDEX uk_identity_account_provider ON public.identity USING btree (account_id, provider_id, team_key) WHERE (disabled_at IS NULL);

CREATE UNIQUE INDEX uk_identity_provider_key ON public.identity USING btree (provider_id, provider_user_key, team_key) WHERE (disabled_at IS NULL);

CREATE UNIQUE INDEX uk_item_event_version ON public.item_event USING btree (workspace_id, item_id, name, item_version) WHERE ((name)::text <> 'scm.pull_request.checks_completed'::text);

CREATE UNIQUE INDEX uk_item_path ON public.item USING btree (workspace_id, parent_id, path) WHERE (path IS NOT NULL);

CREATE UNIQUE INDEX uk_item_source ON public.item USING btree (workspace_id, kind, source_id) WHERE (source_id IS NOT NULL);

CREATE UNIQUE INDEX uk_result_current ON public.result USING btree (practice_id, item_id, developer_id) NULLS NOT DISTINCT WHERE (ended_at IS NULL);

CREATE UNIQUE INDEX uk_result_mark_active ON public.result_mark USING btree (result_id, kind) WHERE (removed_at IS NULL);

CREATE UNIQUE INDEX uk_result_review_claim ON public.result USING btree (review_id, practice_id, item_id, developer_id) NULLS NOT DISTINCT WHERE (evidence_version >= 1);

CREATE UNIQUE INDEX uk_review_batch_schedule_open ON public.review_batch USING btree (schedule_id) WHERE ((schedule_id IS NOT NULL) AND ((status)::text = ANY ((ARRAY['AWAITING_CONFIRMATION'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying])::text[])));

CREATE UNIQUE INDEX uk_review_job_active_review ON public.review_job USING btree (review_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying])::text[]));

CREATE UNIQUE INDEX uk_review_scan_active_workspace ON public.review USING btree (workspace_id) WHERE (((kind)::text = 'SCAN'::text) AND ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying])::text[])));

CREATE UNIQUE INDEX uk_user_provider_login ON public."user" USING btree (provider_id, lower((login)::text));

CREATE UNIQUE INDEX uq_account_export_in_flight ON public.account_export USING btree (account_id) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text]));

CREATE UNIQUE INDEX uq_account_primary_email_active ON public.account USING btree (primary_email) WHERE ((primary_email IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uq_connection_audit_idempotency ON public.connection_audit USING btree (connection_id, event_type, correlation_id) NULLS NOT DISTINCT;

CREATE UNIQUE INDEX uq_connection_one_active_scm_per_workspace ON public.connection USING btree (workspace_id) WHERE (((state)::text = 'ACTIVE'::text) AND ((kind)::text = ANY (ARRAY[('GITHUB'::character varying)::text, ('GITLAB'::character varying)::text])));

CREATE UNIQUE INDEX uq_connection_one_active_slack_per_team ON public.connection USING btree (instance_key) WHERE (((state)::text = 'ACTIVE'::text) AND ((kind)::text = 'SLACK'::text));

CREATE UNIQUE INDEX uq_connection_one_active_slack_per_workspace ON public.connection USING btree (workspace_id) WHERE (((state)::text = 'ACTIVE'::text) AND ((kind)::text = 'SLACK'::text));

CREATE UNIQUE INDEX uq_connection_one_github_installation ON public.connection USING btree (instance_key) WHERE (((kind)::text = 'GITHUB'::text) AND ((state)::text <> 'UNINSTALLED'::text) AND ((config ->> 'type'::text) = 'GITHUB_APP'::text));

CREATE UNIQUE INDEX uq_connection_pending ON public.connection USING btree (workspace_id, kind) WHERE (instance_key IS NULL);

CREATE UNIQUE INDEX uq_issued_jwt_live_successor ON public.issued_jwt USING btree (account_id, predecessor_jti) WHERE ((predecessor_jti IS NOT NULL) AND (revoked_at IS NULL));

CREATE UNIQUE INDEX ux_llm_model_price_open ON public.llm_model_price USING btree (model_id) WHERE (effective_to IS NULL);

CREATE UNIQUE INDEX ux_mentor_conversation_main ON public.mentor_conversation USING btree (workspace_id, account_id) WHERE (parent_conversation_id IS NULL);

CREATE UNIQUE INDEX ux_mentor_entry_request ON public.mentor_entry USING btree (request_id) WHERE (request_id IS NOT NULL);

CREATE UNIQUE INDEX ux_mentor_turn_running ON public.mentor_turn USING btree (conversation_id) WHERE ((status)::text = 'RUNNING'::text);

CREATE UNIQUE INDEX ux_sync_job_active ON public.sync_job USING btree (connection_id) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('RUNNING'::character varying)::text]));

CREATE TRIGGER feedback_copy_fence_monotonic BEFORE UPDATE ON public.feedback_copy FOR EACH ROW EXECUTE FUNCTION public.guard_feedback_copy_fence();

CREATE TRIGGER feedback_decision_append_only BEFORE UPDATE ON public.feedback_decision FOR EACH ROW EXECUTE FUNCTION public.guard_feedback_history();

CREATE TRIGGER feedback_response_append_only BEFORE UPDATE ON public.feedback_response FOR EACH ROW EXECUTE FUNCTION public.guard_feedback_history();

CREATE TRIGGER feedback_send_on_work BEFORE INSERT OR UPDATE ON public.feedback_send FOR EACH ROW EXECUTE FUNCTION public.guard_feedback_send_destination();

CREATE TRIGGER item_event_facts BEFORE UPDATE ON public.item_event FOR EACH ROW EXECUTE FUNCTION public.guard_item_event_facts();

CREATE TRIGGER item_identity BEFORE INSERT OR UPDATE ON public.item FOR EACH ROW EXECUTE FUNCTION public.guard_item_identity();

CREATE CONSTRAINT TRIGGER practice_current_definition_complete AFTER INSERT OR UPDATE ON public.practice DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_practice_current_definition();

CREATE TRIGGER practice_identity_immutable BEFORE UPDATE ON public.practice FOR EACH ROW EXECUTE FUNCTION public.guard_practice_identity();

CREATE TRIGGER practice_version_immutable BEFORE UPDATE ON public.practice_version FOR EACH ROW EXECUTE FUNCTION public.guard_practice_version();

CREATE CONSTRAINT TRIGGER problem_children AFTER INSERT OR DELETE OR UPDATE ON public.problem DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_result_children();

CREATE TRIGGER problem_parent_touch BEFORE INSERT OR DELETE OR UPDATE ON public.problem FOR EACH ROW EXECUTE FUNCTION public.touch_problem_parent();

CREATE CONSTRAINT TRIGGER result_children AFTER INSERT OR UPDATE ON public.result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_result_children();

CREATE TRIGGER result_facts BEFORE UPDATE ON public.result FOR EACH ROW EXECUTE FUNCTION public.guard_result_facts();

CREATE TRIGGER result_insert BEFORE INSERT ON public.result FOR EACH ROW EXECUTE FUNCTION public.guard_result_insert();

CREATE CONSTRAINT TRIGGER review_item_scope AFTER INSERT OR UPDATE ON public.review_item DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_review_item_scope();

CREATE TRIGGER review_item_write BEFORE INSERT OR UPDATE ON public.review_item FOR EACH ROW EXECUTE FUNCTION public.guard_review_item_write();

CREATE TRIGGER trg_consent_decision_append_only BEFORE DELETE OR UPDATE ON public.consent_decision FOR EACH ROW EXECUTE FUNCTION public.enforce_consent_decision_append_only();

CREATE TRIGGER trg_reserve_workspace_slug BEFORE INSERT OR UPDATE OF slug ON public.workspace FOR EACH ROW EXECUTE FUNCTION public.reserve_workspace_slug();

ALTER TABLE ONLY public.team_repository_permission
    ADD CONSTRAINT "FK7qxvqq8p6690vtdux47lsg8b1" FOREIGN KEY (team_id) REFERENCES public.team(id);

ALTER TABLE ONLY public.team_repository_permission
    ADD CONSTRAINT "FK92gtctw6ca02527qjja7gns9f" FOREIGN KEY (repository_id) REFERENCES public.repository(id);

ALTER TABLE ONLY public.repository_to_monitor
    ADD CONSTRAINT "FKdkxnkm4a2wyw0d5k63gh2st64" FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.team_membership
    ADD CONSTRAINT "FKnkpwi3whks92uvhn5qe71v4k6" FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.team_membership
    ADD CONSTRAINT "FKrf92vmiawfvyhxcmigcg10opm" FOREIGN KEY (team_id) REFERENCES public.team(id);

ALTER TABLE ONLY public.label
    ADD CONSTRAINT fk2951edbl9g9y8ee1q97e2ff75 FOREIGN KEY (repository_id) REFERENCES public.repository(id);

ALTER TABLE ONLY public.issue_assignee
    ADD CONSTRAINT fk2cfu8w8wjb9vosy4hbrme0rqe FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.pull_request_requested_reviewers
    ADD CONSTRAINT fk6dld06xx8rh9xhqfnca070a0i FOREIGN KEY (pull_request_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fk76s4b6ncspm9bk35y49xh4s9t FOREIGN KEY (repository_id) REFERENCES public.repository(id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fk7t1o4tuel06m9bn4dppqmiod6 FOREIGN KEY (milestone_id) REFERENCES public.milestone(id);

ALTER TABLE ONLY public.issue_comment
    ADD CONSTRAINT fk8wy5rxggrte2ntcq80g7o7210 FOREIGN KEY (issue_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.activity_automation
    ADD CONSTRAINT fk_activity_automation_user FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.activity_automation
    ADD CONSTRAINT fk_activity_automation_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.activity_event
    ADD CONSTRAINT fk_activity_event_actor FOREIGN KEY (actor_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.activity_event
    ADD CONSTRAINT fk_activity_event_repository FOREIGN KEY (repository_id) REFERENCES public.repository(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.activity_event
    ADD CONSTRAINT fk_activity_event_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.commit_contributor
    ADD CONSTRAINT fk_commit_contributor_commit FOREIGN KEY (commit_id) REFERENCES public.git_commit(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.commit_contributor
    ADD CONSTRAINT fk_commit_contributor_user FOREIGN KEY (user_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.commit_file_change
    ADD CONSTRAINT fk_commit_file_change_commit FOREIGN KEY (commit_id) REFERENCES public.git_commit(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.commit_pull_request
    ADD CONSTRAINT fk_commit_pr_commit FOREIGN KEY (commit_id) REFERENCES public.git_commit(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.commit_pull_request
    ADD CONSTRAINT fk_commit_pr_pull_request FOREIGN KEY (pull_request_id) REFERENCES public.issue(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.connection_activity
    ADD CONSTRAINT fk_connection_activity_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.connection_activity
    ADD CONSTRAINT fk_connection_activity_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.connection_audit
    ADD CONSTRAINT fk_connection_audit_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id);

ALTER TABLE ONLY public.connection
    ADD CONSTRAINT fk_connection_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.consent_decision
    ADD CONSTRAINT fk_consent_decision_account FOREIGN KEY (account_id) REFERENCES public.account(id);

ALTER TABLE ONLY public.consumer_dead_letter
    ADD CONSTRAINT fk_consumer_dead_letter_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.git_commit
    ADD CONSTRAINT fk_git_commit_author FOREIGN KEY (author_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.git_commit
    ADD CONSTRAINT fk_git_commit_committer FOREIGN KEY (committer_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.git_commit
    ADD CONSTRAINT fk_git_commit_repository FOREIGN KEY (repository_id) REFERENCES public.repository(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_blocking
    ADD CONSTRAINT fk_issue_blocking_blocked FOREIGN KEY (blocked_issue_id) REFERENCES public.issue(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_blocking
    ADD CONSTRAINT fk_issue_blocking_blocking FOREIGN KEY (blocking_issue_id) REFERENCES public.issue(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_comment
    ADD CONSTRAINT fk_issue_comment_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fk_issue_issue_type FOREIGN KEY (issue_type_id) REFERENCES public.issue_type(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fk_issue_parent_issue FOREIGN KEY (parent_issue_id) REFERENCES public.issue(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fk_issue_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.issue_type
    ADD CONSTRAINT fk_issue_type_organization FOREIGN KEY (organization_id) REFERENCES public.organization(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.label
    ADD CONSTRAINT fk_label_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.llm_model
    ADD CONSTRAINT fk_llm_model_connection FOREIGN KEY (connection_id) REFERENCES public.llm_connection(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.llm_model_price
    ADD CONSTRAINT fk_llm_model_price_model FOREIGN KEY (model_id) REFERENCES public.llm_model(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT fk_milestone_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.workspace_onboarding_settings
    ADD CONSTRAINT fk_onboarding_settings_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT fk_organization_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.passkey_challenge
    ADD CONSTRAINT fk_passkey_challenge_account FOREIGN KEY (account_id) REFERENCES public.account(id);

ALTER TABLE ONLY public.passkey_credential
    ADD CONSTRAINT fk_passkey_credential_account FOREIGN KEY (account_id) REFERENCES public.account(id);

ALTER TABLE ONLY public.passkey_recovery_code
    ADD CONSTRAINT fk_passkey_recovery_code_account FOREIGN KEY (account_id) REFERENCES public.account(id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fk_pr_review_comment_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fk_pr_review_comment_reply FOREIGN KEY (in_reply_to_id) REFERENCES public.pull_request_review_comment(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fk_pr_review_comment_thread FOREIGN KEY (thread_id) REFERENCES public.pull_request_review_thread(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.pull_request_review
    ADD CONSTRAINT fk_pr_review_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT fk_pr_review_thread_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT fk_pr_review_thread_pull_request FOREIGN KEY (pull_request_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT fk_pr_review_thread_root_comment FOREIGN KEY (root_comment_id) REFERENCES public.pull_request_review_comment(id);

ALTER TABLE ONLY public.practice_group
    ADD CONSTRAINT fk_practice_group_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT fk_practice_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.pull_request_closing_issue
    ADD CONSTRAINT fk_pull_request_closing_issue_issue FOREIGN KEY (issue_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.pull_request_closing_issue
    ADD CONSTRAINT fk_pull_request_closing_issue_pull_request FOREIGN KEY (pull_request_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.pull_request_requested_team
    ADD CONSTRAINT fk_pull_request_requested_team_pull_request FOREIGN KEY (pull_request_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.pull_request_requested_team
    ADD CONSTRAINT fk_pull_request_requested_team_team FOREIGN KEY (team_id) REFERENCES public.team(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT fk_pull_request_review_thread_resolved_by FOREIGN KEY (resolved_by_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.repository_collaborator
    ADD CONSTRAINT fk_repository_collaborator_repository FOREIGN KEY (repository_id) REFERENCES public.repository(id);

ALTER TABLE ONLY public.repository_collaborator
    ADD CONSTRAINT fk_repository_collaborator_user FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.repository
    ADD CONSTRAINT fk_repository_organization FOREIGN KEY (organization_id) REFERENCES public.organization(id);

ALTER TABLE ONLY public.repository
    ADD CONSTRAINT fk_repository_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.research_disclosure_subject
    ADD CONSTRAINT fk_research_disclosure_subject_disclosure FOREIGN KEY (disclosure_id, workspace_id) REFERENCES public.research_disclosure(id, workspace_id);

ALTER TABLE ONLY public.sync_job
    ADD CONSTRAINT fk_sync_job_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id);

ALTER TABLE ONLY public.sync_job
    ADD CONSTRAINT fk_sync_job_connection_workspace FOREIGN KEY (connection_id, workspace_id) REFERENCES public.connection(id, workspace_id);

ALTER TABLE ONLY public.sync_job
    ADD CONSTRAINT fk_sync_job_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.team
    ADD CONSTRAINT fk_team_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT fk_user_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT fk_workspace_agent_binding_instance_model FOREIGN KEY (instance_model_id) REFERENCES public.llm_model(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT fk_workspace_agent_binding_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT fk_workspace_agent_binding_workspace_model FOREIGN KEY (workspace_model_id) REFERENCES public.workspace_llm_model(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.workspace_slug_history
    ADD CONSTRAINT fk_workspace_slug_history_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_label_filter
    ADD CONSTRAINT fk_workspace_team_label_filter_label FOREIGN KEY (label_id) REFERENCES public.label(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_label_filter
    ADD CONSTRAINT fk_workspace_team_label_filter_team FOREIGN KEY (team_id) REFERENCES public.team(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_label_filter
    ADD CONSTRAINT fk_workspace_team_label_filter_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_repository_settings
    ADD CONSTRAINT fk_workspace_team_repo_settings_repository FOREIGN KEY (repository_id) REFERENCES public.repository(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_repository_settings
    ADD CONSTRAINT fk_workspace_team_repo_settings_team FOREIGN KEY (team_id) REFERENCES public.team(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_repository_settings
    ADD CONSTRAINT fk_workspace_team_repo_settings_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_settings
    ADD CONSTRAINT fk_workspace_team_settings_team FOREIGN KEY (team_id) REFERENCES public.team(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_settings
    ADD CONSTRAINT fk_workspace_team_settings_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_llm_connection
    ADD CONSTRAINT fk_ws_llm_connection_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT fk_ws_llm_model_connection FOREIGN KEY (connection_id) REFERENCES public.workspace_llm_connection(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT fk_ws_llm_model_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT fkbjhs37s6qmqtd330gu9mit6w0 FOREIGN KEY (repository_id) REFERENCES public.repository(id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fkbx1g5jpdegymhyv9pbk2jdgfw FOREIGN KEY (review_id) REFERENCES public.pull_request_review(id);

ALTER TABLE ONLY public.issue_comment
    ADD CONSTRAINT fkdy6oeojymud1wna20olqgyt31 FOREIGN KEY (author_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.pull_request_review
    ADD CONSTRAINT fkeehfcwrodfu61gremlcvhgir5 FOREIGN KEY (author_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT fkg6ieho7gomiumy85puy6l13f1 FOREIGN KEY (creator_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.pull_request_review
    ADD CONSTRAINT fkio96gq2jetvy6a4in9nl8vkvd FOREIGN KEY (pull_request_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.pull_request_requested_reviewers
    ADD CONSTRAINT fkioq4g5aksr97l6qyl4g5l63tn FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.issue_label
    ADD CONSTRAINT fkit5n9c0frugu5m8xqsxtps63r FOREIGN KEY (issue_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.issue_assignee
    ADD CONSTRAINT fkocgmsva4p5e8ic9k5dbjqa15u FOREIGN KEY (issue_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fkohqvdiswptbm0h8cniq7r1tgq FOREIGN KEY (pull_request_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fkqvnu6vslj5txt8xencru8m6x4 FOREIGN KEY (merged_by_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fkrwr6v8fiqetuiuvfjcvie8s85 FOREIGN KEY (author_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fktl08ieowbl171xem2bciho7kw FOREIGN KEY (author_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.issue_label
    ADD CONSTRAINT fkxbk5rr30kkb6k4ech7x4vh9h FOREIGN KEY (label_id) REFERENCES public.label(id);

ALTER TABLE ONLY public.account_export
    ADD CONSTRAINT sfk_account_export_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.account_feature
    ADD CONSTRAINT sfk_account_feature_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.account_public_activity_hidden_by_default
    ADD CONSTRAINT sfk_account_public_activity_hidden_by_default_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_account_id FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_acting_account_id FOREIGN KEY (acting_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_git_provider_id FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_identity FOREIGN KEY (identity_id) REFERENCES public.identity(id) ON DELETE SET NULL;

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_viewed_account FOREIGN KEY (viewed_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_viewed_user_id FOREIGN KEY (viewed_actor_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_workspace_id FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.client_session
    ADD CONSTRAINT sfk_client_session_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.client_sign_in_handoff
    ADD CONSTRAINT sfk_client_sign_in_handoff_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.config_audit_event
    ADD CONSTRAINT sfk_config_audit_event_acting_account_id FOREIGN KEY (acting_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.config_audit_event
    ADD CONSTRAINT sfk_config_audit_event_actor_account_id FOREIGN KEY (actor_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.config_audit_event
    ADD CONSTRAINT sfk_config_audit_event_workspace_id FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.connection_audit
    ADD CONSTRAINT sfk_connection_audit_actor_account FOREIGN KEY (actor_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.consumer_dead_letter
    ADD CONSTRAINT sfk_consumer_dead_letter_replayed_by FOREIGN KEY (replayed_by) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.feedback_basis
    ADD CONSTRAINT sfk_feedback_basis_feedback FOREIGN KEY (feedback_id, workspace_id) REFERENCES public.feedback(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_basis
    ADD CONSTRAINT sfk_feedback_basis_feedback_person FOREIGN KEY (feedback_id, developer_id, workspace_id) REFERENCES public.feedback(id, developer_id, workspace_id) ON DELETE CASCADE DEFERRABLE;

ALTER TABLE ONLY public.feedback_basis
    ADD CONSTRAINT sfk_feedback_basis_problem FOREIGN KEY (problem_id, result_id, workspace_id) REFERENCES public.problem(id, result_id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_basis
    ADD CONSTRAINT sfk_feedback_basis_result FOREIGN KEY (result_id, workspace_id) REFERENCES public.result(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_basis
    ADD CONSTRAINT sfk_feedback_basis_result_person FOREIGN KEY (result_id, developer_id, workspace_id) REFERENCES public.result(id, developer_id, workspace_id) ON DELETE CASCADE DEFERRABLE;

ALTER TABLE ONLY public.feedback_basis
    ADD CONSTRAINT sfk_feedback_basis_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.feedback_copy
    ADD CONSTRAINT sfk_feedback_copy_feedback FOREIGN KEY (feedback_id, workspace_id) REFERENCES public.feedback(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_copy
    ADD CONSTRAINT sfk_feedback_copy_mentor_entry FOREIGN KEY (mentor_entry_id, workspace_id) REFERENCES public.mentor_entry(id, workspace_id) ON DELETE SET NULL (mentor_entry_id);

ALTER TABLE ONLY public.feedback_copy
    ADD CONSTRAINT sfk_feedback_copy_mentor_turn FOREIGN KEY (mentor_turn_id, workspace_id) REFERENCES public.mentor_turn(id, workspace_id) ON DELETE SET NULL (mentor_turn_id);

ALTER TABLE ONLY public.feedback_copy
    ADD CONSTRAINT sfk_feedback_copy_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.feedback_decision
    ADD CONSTRAINT sfk_feedback_decision_actor FOREIGN KEY (actor_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.feedback_decision
    ADD CONSTRAINT sfk_feedback_decision_feedback FOREIGN KEY (feedback_id, workspace_id) REFERENCES public.feedback(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_decision
    ADD CONSTRAINT sfk_feedback_decision_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT sfk_feedback_developer FOREIGN KEY (workspace_id, developer_id) REFERENCES public.membership(workspace_id, account_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT sfk_feedback_item FOREIGN KEY (item_id, workspace_id) REFERENCES public.item(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT sfk_feedback_replaces FOREIGN KEY (replaces_id, workspace_id) REFERENCES public.feedback(id, workspace_id) ON DELETE SET NULL (replaces_id);

ALTER TABLE ONLY public.feedback_response
    ADD CONSTRAINT sfk_feedback_response_feedback FOREIGN KEY (feedback_id, workspace_id) REFERENCES public.feedback(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_response
    ADD CONSTRAINT sfk_feedback_response_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT sfk_feedback_review FOREIGN KEY (review_id, workspace_id) REFERENCES public.review(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.feedback_send
    ADD CONSTRAINT sfk_feedback_send_feedback FOREIGN KEY (feedback_id, workspace_id) REFERENCES public.feedback(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_send
    ADD CONSTRAINT sfk_feedback_send_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT sfk_feedback_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.identity
    ADD CONSTRAINT sfk_identity_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.identity
    ADD CONSTRAINT sfk_identity_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.identity_restriction
    ADD CONSTRAINT sfk_identity_restriction_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.identity_restriction
    ADD CONSTRAINT sfk_identity_restriction_request FOREIGN KEY (active_request_id) REFERENCES public.person_data_request(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.instance_llm_settings
    ADD CONSTRAINT sfk_instance_llm_settings_updated_by_account FOREIGN KEY (updated_by_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.instance_settings
    ADD CONSTRAINT sfk_instance_settings_changed_by_account FOREIGN KEY (silent_mode_changed_by_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issued_jwt
    ADD CONSTRAINT sfk_issued_jwt_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issued_jwt
    ADD CONSTRAINT sfk_issued_jwt_session FOREIGN KEY (session_id) REFERENCES public.client_session(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.item_event
    ADD CONSTRAINT sfk_item_event_actor FOREIGN KEY (actor_user_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.item_event
    ADD CONSTRAINT sfk_item_event_item FOREIGN KEY (item_id, workspace_id) REFERENCES public.item(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.item_event
    ADD CONSTRAINT sfk_item_event_requester FOREIGN KEY (requested_by_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.item_event
    ADD CONSTRAINT sfk_item_event_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.item
    ADD CONSTRAINT sfk_item_parent FOREIGN KEY (parent_id, workspace_id) REFERENCES public.item(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.item
    ADD CONSTRAINT sfk_item_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.llm_call_permit
    ADD CONSTRAINT sfk_llm_call_permit_pool FOREIGN KEY (pool_id) REFERENCES public.llm_call_pool(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.llm_model
    ADD CONSTRAINT sfk_llm_model_call_pool FOREIGN KEY (call_pool_id) REFERENCES public.llm_call_pool(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.llm_model_workspace_grant
    ADD CONSTRAINT sfk_llm_model_grant_model FOREIGN KEY (model_id) REFERENCES public.llm_model(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.llm_model_workspace_grant
    ADD CONSTRAINT sfk_llm_model_grant_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.llm_usage_event
    ADD CONSTRAINT sfk_llm_usage_event_review FOREIGN KEY (review_id, workspace_id) REFERENCES public.review(id, workspace_id) ON DELETE SET NULL (review_id);

ALTER TABLE ONLY public.llm_usage_event
    ADD CONSTRAINT sfk_llm_usage_event_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.membership
    ADD CONSTRAINT sfk_membership_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.membership
    ADD CONSTRAINT sfk_membership_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.mentor_conversation
    ADD CONSTRAINT sfk_mentor_conversation_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.mentor_conversation
    ADD CONSTRAINT sfk_mentor_conversation_parent FOREIGN KEY (parent_conversation_id, workspace_id) REFERENCES public.mentor_conversation(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.mentor_conversation
    ADD CONSTRAINT sfk_mentor_conversation_parent_entry FOREIGN KEY (parent_entry_id, workspace_id) REFERENCES public.mentor_entry(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.mentor_conversation
    ADD CONSTRAINT sfk_mentor_conversation_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.mentor_entry
    ADD CONSTRAINT sfk_mentor_entry_conversation FOREIGN KEY (conversation_id, workspace_id) REFERENCES public.mentor_conversation(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.mentor_entry
    ADD CONSTRAINT sfk_mentor_entry_turn FOREIGN KEY (turn_id, workspace_id) REFERENCES public.mentor_turn(id, workspace_id) ON DELETE SET NULL (turn_id);

ALTER TABLE ONLY public.mentor_entry
    ADD CONSTRAINT sfk_mentor_entry_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.mentor_turn_attempt_usage
    ADD CONSTRAINT sfk_mentor_turn_attempt_usage_turn_workspace FOREIGN KEY (turn_id, workspace_id) REFERENCES public.mentor_turn(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.mentor_turn_attempt_usage
    ADD CONSTRAINT sfk_mentor_turn_attempt_usage_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.mentor_turn
    ADD CONSTRAINT sfk_mentor_turn_conversation FOREIGN KEY (conversation_id, workspace_id) REFERENCES public.mentor_conversation(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.mentor_turn
    ADD CONSTRAINT sfk_mentor_turn_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.notification_subscription
    ADD CONSTRAINT sfk_notification_subscription_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.oauth_state_nonce
    ADD CONSTRAINT sfk_oauth_state_nonce_actor_account FOREIGN KEY (actor_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.outline_collection
    ADD CONSTRAINT sfk_outline_collection_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id);

ALTER TABLE ONLY public.outline_collection
    ADD CONSTRAINT sfk_outline_collection_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.outline_document
    ADD CONSTRAINT sfk_outline_document_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id);

ALTER TABLE ONLY public.outline_document
    ADD CONSTRAINT sfk_outline_document_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.person_data_request
    ADD CONSTRAINT sfk_person_data_request_administrator FOREIGN KEY (administrator_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT sfk_practice_current_version FOREIGN KEY (current_version_id, id, workspace_id) REFERENCES public.practice_version(id, practice_id, workspace_id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT sfk_practice_group FOREIGN KEY (group_id, workspace_id) REFERENCES public.practice_group(id, workspace_id) ON DELETE SET NULL (group_id);

ALTER TABLE ONLY public.practice_version
    ADD CONSTRAINT sfk_practice_version_creator FOREIGN KEY (created_by_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.practice_version
    ADD CONSTRAINT sfk_practice_version_practice FOREIGN KEY (practice_id, workspace_id) REFERENCES public.practice(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.practice_version
    ADD CONSTRAINT sfk_practice_version_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.problem
    ADD CONSTRAINT sfk_problem_result FOREIGN KEY (result_id, workspace_id) REFERENCES public.result(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.problem
    ADD CONSTRAINT sfk_problem_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.product_feedback
    ADD CONSTRAINT sfk_product_feedback_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.product_feedback
    ADD CONSTRAINT sfk_product_feedback_resolver FOREIGN KEY (resolved_by_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.product_feedback
    ADD CONSTRAINT sfk_product_feedback_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.research_disclosure
    ADD CONSTRAINT sfk_research_disclosure_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.result
    ADD CONSTRAINT sfk_result_developer FOREIGN KEY (workspace_id, developer_id) REFERENCES public.membership(workspace_id, account_id) ON DELETE RESTRICT DEFERRABLE;

ALTER TABLE ONLY public.result
    ADD CONSTRAINT sfk_result_item FOREIGN KEY (item_id, workspace_id) REFERENCES public.item(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.result_mark
    ADD CONSTRAINT sfk_result_mark_creator FOREIGN KEY (created_by_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.result_mark
    ADD CONSTRAINT sfk_result_mark_remover FOREIGN KEY (removed_by_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.result_mark
    ADD CONSTRAINT sfk_result_mark_result FOREIGN KEY (result_id, workspace_id) REFERENCES public.result(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.result_mark
    ADD CONSTRAINT sfk_result_mark_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.result
    ADD CONSTRAINT sfk_result_practice FOREIGN KEY (practice_id, workspace_id) REFERENCES public.practice(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.result
    ADD CONSTRAINT sfk_result_review FOREIGN KEY (review_id, workspace_id) REFERENCES public.review(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.result
    ADD CONSTRAINT sfk_result_version FOREIGN KEY (practice_version_id, practice_id, workspace_id) REFERENCES public.practice_version(id, practice_id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.result
    ADD CONSTRAINT sfk_result_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review
    ADD CONSTRAINT sfk_review_batch FOREIGN KEY (batch_id, workspace_id) REFERENCES public.review_batch(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_batch
    ADD CONSTRAINT sfk_review_batch_confirmer FOREIGN KEY (confirmed_by_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.review_batch
    ADD CONSTRAINT sfk_review_batch_cursor FOREIGN KEY (cursor_item_id, workspace_id) REFERENCES public.item(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_batch
    ADD CONSTRAINT sfk_review_batch_requester FOREIGN KEY (requested_by_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.review_batch
    ADD CONSTRAINT sfk_review_batch_schedule FOREIGN KEY (schedule_id, workspace_id) REFERENCES public.review_schedule(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_batch_target
    ADD CONSTRAINT sfk_review_batch_target_batch FOREIGN KEY (batch_id, workspace_id) REFERENCES public.review_batch(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_batch_target
    ADD CONSTRAINT sfk_review_batch_target_item FOREIGN KEY (item_id, workspace_id) REFERENCES public.item(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_batch_target
    ADD CONSTRAINT sfk_review_batch_target_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_batch
    ADD CONSTRAINT sfk_review_batch_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_composition_basis
    ADD CONSTRAINT sfk_review_composition_basis_job FOREIGN KEY (job_id, workspace_id) REFERENCES public.review_job(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_composition_basis
    ADD CONSTRAINT sfk_review_composition_basis_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review
    ADD CONSTRAINT sfk_review_event FOREIGN KEY (event_id, workspace_id) REFERENCES public.item_event(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review
    ADD CONSTRAINT sfk_review_item FOREIGN KEY (item_id, workspace_id) REFERENCES public.item(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_item
    ADD CONSTRAINT sfk_review_item_item FOREIGN KEY (item_id, workspace_id) REFERENCES public.item(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_item
    ADD CONSTRAINT sfk_review_item_job FOREIGN KEY (job_id, workspace_id) REFERENCES public.review_job(id, workspace_id) ON DELETE SET NULL (job_id);

ALTER TABLE ONLY public.review_item
    ADD CONSTRAINT sfk_review_item_practice FOREIGN KEY (review_id, practice_id, workspace_id) REFERENCES public.review_practice(review_id, practice_id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_item
    ADD CONSTRAINT sfk_review_item_result FOREIGN KEY (result_id, practice_id, item_id, workspace_id) REFERENCES public.result(id, practice_id, item_id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_item
    ADD CONSTRAINT sfk_review_item_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_job_attempt_charge
    ADD CONSTRAINT sfk_review_job_attempt_charge_usage FOREIGN KEY (job_id, attempt, workspace_id) REFERENCES public.review_job_attempt_usage(job_id, attempt, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_job_attempt_usage
    ADD CONSTRAINT sfk_review_job_attempt_usage_job FOREIGN KEY (job_id, workspace_id) REFERENCES public.review_job(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_job_attempt_usage
    ADD CONSTRAINT sfk_review_job_attempt_usage_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_job
    ADD CONSTRAINT sfk_review_job_review FOREIGN KEY (review_id, workspace_id) REFERENCES public.review(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_job_script_run
    ADD CONSTRAINT sfk_review_job_script_run_job FOREIGN KEY (job_id, workspace_id) REFERENCES public.review_job(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_job_script_run
    ADD CONSTRAINT sfk_review_job_script_run_version FOREIGN KEY (practice_version_id, practice_id, workspace_id) REFERENCES public.practice_version(id, practice_id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_job_script_run
    ADD CONSTRAINT sfk_review_job_script_run_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_job_script_usage
    ADD CONSTRAINT sfk_review_job_script_usage_job FOREIGN KEY (job_id, workspace_id) REFERENCES public.review_job(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_job_script_usage
    ADD CONSTRAINT sfk_review_job_script_usage_practice FOREIGN KEY (practice_id, workspace_id) REFERENCES public.practice(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_job_script_usage
    ADD CONSTRAINT sfk_review_job_script_usage_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_job
    ADD CONSTRAINT sfk_review_job_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_practice
    ADD CONSTRAINT sfk_review_practice_practice FOREIGN KEY (practice_id, workspace_id) REFERENCES public.practice(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_practice
    ADD CONSTRAINT sfk_review_practice_review FOREIGN KEY (review_id, workspace_id) REFERENCES public.review(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_practice
    ADD CONSTRAINT sfk_review_practice_version FOREIGN KEY (practice_version_id, practice_id, workspace_id) REFERENCES public.practice_version(id, practice_id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_practice
    ADD CONSTRAINT sfk_review_practice_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review
    ADD CONSTRAINT sfk_review_requester FOREIGN KEY (requested_by_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.review_schedule
    ADD CONSTRAINT sfk_review_schedule_creator FOREIGN KEY (created_by_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.review_schedule
    ADD CONSTRAINT sfk_review_schedule_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review_scope_repository
    ADD CONSTRAINT sfk_review_scope_repository_monitor FOREIGN KEY (repository_monitor_id, workspace_id) REFERENCES public.repository_to_monitor(id, workspace_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_scope_repository
    ADD CONSTRAINT sfk_review_scope_repository_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.review
    ADD CONSTRAINT sfk_review_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.slack_channel_consent_event
    ADD CONSTRAINT sfk_slack_channel_consent_event_actor FOREIGN KEY (actor_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.slack_channel_consent_event
    ADD CONSTRAINT sfk_slack_channel_consent_event_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.slack_message
    ADD CONSTRAINT sfk_slack_message_author FOREIGN KEY (workspace_id, author_account_id) REFERENCES public.membership(workspace_id, account_id) ON DELETE SET NULL (author_account_id);

ALTER TABLE ONLY public.slack_message
    ADD CONSTRAINT sfk_slack_message_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.slack_monitored_channel
    ADD CONSTRAINT sfk_slack_monitored_channel_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.slack_participant_consent
    ADD CONSTRAINT sfk_slack_participant_consent_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.slack_thread
    ADD CONSTRAINT sfk_slack_thread_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT sfk_workspace_agent_binding_workspace_model_scope FOREIGN KEY (workspace_model_id, workspace_id) REFERENCES public.workspace_llm_model(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT sfk_workspace_organization FOREIGN KEY (organization_id) REFERENCES public.organization(id);

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT sfk_ws_llm_model_call_pool FOREIGN KEY (call_pool_id) REFERENCES public.llm_call_pool(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT sfk_ws_llm_model_connection_scope FOREIGN KEY (connection_id, workspace_id) REFERENCES public.workspace_llm_connection(id, workspace_id) ON DELETE CASCADE;

