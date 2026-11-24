-- The seeded turn's run stores the conversation's owner and the position of the question that it answered.
DO $$
BEGIN
    IF (SELECT r.account_id || ':' || r.conversation_id || ':' || r.subject_seq
        FROM agent_run r JOIN conversation_turn t ON (t.run_id, t.workspace_id) = (r.id, r.workspace_id)
        WHERE t.id = '00000000-0000-0000-0000-000000979403') IS DISTINCT FROM '979401:00000000-0000-0000-0000-000000979402:2' THEN
        RAISE EXCEPTION 'The seeded turn''s run does not store its owner and the position of its question';
    END IF;
END
$$;
