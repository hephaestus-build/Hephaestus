-- Shared post-upgrade proof for database tests and the release upgrade drill.
DO $$
BEGIN
 IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema='public'
  AND (table_name LIKE '\_released\_%' ESCAPE '\' OR table_name IN
   ('observation','observation_invalidation','artifact_signal','agent_job','identity_link','workspace_membership','person_suppression','public_activity_objection'))) THEN
  RAISE EXCEPTION 'Cutover left released tables';
 END IF;
 IF EXISTS (SELECT FROM result r LEFT JOIN item i ON (i.id,i.workspace_id)=(r.item_id,r.workspace_id) WHERE i.id IS NULL) THEN
  RAISE EXCEPTION 'Result has no tenant-owned item';
 END IF;
 IF EXISTS (SELECT FROM result WHERE ended_at IS NULL GROUP BY workspace_id,practice_id,item_id,developer_id HAVING count(*)>1) THEN
  RAISE EXCEPTION 'More than one current result';
 END IF;
 IF EXISTS (SELECT FROM result r WHERE (r.outcome='NOT_MET') <> EXISTS(SELECT FROM problem p WHERE (p.result_id,p.workspace_id)=(r.id,r.workspace_id))) THEN
  RAISE EXCEPTION 'Result outcome disagrees with problem presence';
 END IF;
 IF EXISTS (SELECT FROM feedback_basis b JOIN result r ON (r.id,r.workspace_id)=(b.result_id,b.workspace_id)
  JOIN feedback f ON (f.id,f.workspace_id)=(b.feedback_id,b.workspace_id)
  WHERE b.developer_id IS DISTINCT FROM r.developer_id OR b.developer_id IS DISTINCT FROM f.developer_id) THEN
  RAISE EXCEPTION 'Feedback basis changes the developer';
 END IF;
 IF EXISTS (SELECT FROM feedback_copy c JOIN feedback_send s ON (s.feedback_id,s.workspace_id)=(c.feedback_id,c.workspace_id)
  WHERE (c.state='POSTED' OR c.write_started_at IS NOT NULL) AND s.action='POST' AND s.state='PENDING') THEN
  RAISE EXCEPTION 'Existing or uncertain copy is queued for a repeat POST';
 END IF;
 IF EXISTS (SELECT FROM feedback WHERE id='00000000-0000-0000-0000-000000992161')
 AND NOT EXISTS (SELECT FROM feedback_copy WHERE id='00000000-0000-0000-0000-000000992171'
  AND state='POSTED' AND external_ref='provider:123') THEN
  RAISE EXCEPTION 'Delivered source receipt was not preserved';
 END IF;
 IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema='public'
  AND table_name IN ('chat_thread','chat_message','chat_message_vote','mentor_slack_thread')) THEN
  RAISE EXCEPTION 'Upgrade left Heph chat tables';
 END IF;
 IF EXISTS (SELECT FROM feedback_copy WHERE kind='CHAT_TURN' AND (mentor_entry_id IS NOT NULL OR mentor_turn_id IS NOT NULL)) THEN
  RAISE EXCEPTION 'Conversation copy kept a binding to dropped Heph chat history';
 END IF;
 IF EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND NOT convalidated) THEN
  RAISE EXCEPTION 'Cutover left an unvalidated constraint';
 END IF;
 IF NOT EXISTS (SELECT FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
  WHERE c.relname='uk_result_current' AND i.indisvalid AND i.indnullsnotdistinct AND i.indpred IS NOT NULL) THEN
  RAISE EXCEPTION 'Current result uniqueness is missing';
 END IF;
END $$;
