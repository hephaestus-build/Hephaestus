-- An answered question of the previous release: a conversation, its question at position 2 after an earlier answer,
-- the turn that answered it and the turn's ended run. The seed runs on every release that an upgrade can start from.
-- The 1.0 baseline (v1.0.0-rc.1) names the tables mentor_* and has no runs, so the chain gives the turn its run. A
-- later release has conversation_* and runs. Its turns and entries store their account from the conversation-row
-- conversion on, and its runs store their owner and position from this conversion on.
INSERT INTO workspace (id, slug, display_name, account_login, account_type, status)
VALUES (979400, 'cutover-run-owner', 'Cutover run owner', 'cutover-run-owner', 'ORG', 'ACTIVE');
INSERT INTO account (id, display_name) VALUES (979401, 'Cutover run owner');
DO $$
BEGIN
    IF to_regclass('public.mentor_turn') IS NOT NULL THEN
        INSERT INTO mentor_conversation (id, workspace_id, account_id, created_at, last_activity_at)
        VALUES ('00000000-0000-0000-0000-000000979402', 979400, 979401, now(), now());
        INSERT INTO mentor_turn (id, workspace_id, conversation_id, request_id, status, surface, attempt, created_at,
                                 started_at, finished_at)
        VALUES ('00000000-0000-0000-0000-000000979403', 979400, '00000000-0000-0000-0000-000000979402',
                'cutover-run-owner', 'DONE', 'APP', 1, now(), now(), now());
        INSERT INTO mentor_entry (id, workspace_id, conversation_id, seq, kind, surface, text, turn_id, created_at)
        VALUES ('00000000-0000-0000-0000-000000979404', 979400, '00000000-0000-0000-0000-000000979402', 1, 'HEPH',
                'APP', 'An earlier answer.', NULL, now()),
               ('00000000-0000-0000-0000-000000979405', 979400, '00000000-0000-0000-0000-000000979402', 2, 'USER',
                'APP', 'A question of the previous release.', '00000000-0000-0000-0000-000000979403', now());
        RETURN;
    END IF;
    INSERT INTO conversation (id, workspace_id, account_id, created_at, last_activity_at)
    VALUES ('00000000-0000-0000-0000-000000979402', 979400, 979401, now(), now());
    IF EXISTS (SELECT FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'agent_run' AND column_name = 'account_id') THEN
        INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                               available_at, start_within_seconds, stop_reason, created_at, ended_at, account_id,
                               conversation_id, subject_seq)
        VALUES ('00000000-0000-0000-0000-000000979406', 979400, 'TURN', 'INTERACTIVE',
                'conversation:00000000-0000-0000-0000-000000979402', 'ENDED', 0, 1, now(), 180, 'COMPLETED', now(),
                now(), 979401, '00000000-0000-0000-0000-000000979402', 2);
    ELSE
        INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                               available_at, start_within_seconds, stop_reason, created_at, ended_at)
        VALUES ('00000000-0000-0000-0000-000000979406', 979400, 'TURN', 'INTERACTIVE',
                'conversation:00000000-0000-0000-0000-000000979402', 'ENDED', 0, 1, now(), 180, 'COMPLETED', now(),
                now());
    END IF;
    IF EXISTS (SELECT FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'conversation_turn' AND column_name = 'account_id') THEN
        INSERT INTO conversation_turn (id, workspace_id, conversation_id, account_id, request_id, status, surface,
                                       attempt, run_id, created_at, started_at, finished_at)
        VALUES ('00000000-0000-0000-0000-000000979403', 979400, '00000000-0000-0000-0000-000000979402', 979401,
                'cutover-run-owner', 'DONE', 'APP', 1, '00000000-0000-0000-0000-000000979406', now(), now(), now());
        INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                                        turn_id, created_at)
        VALUES ('00000000-0000-0000-0000-000000979404', 979400, '00000000-0000-0000-0000-000000979402', 979401, 1,
                'ANSWER', 'APP', 'An earlier answer.', NULL, now()),
               ('00000000-0000-0000-0000-000000979405', 979400, '00000000-0000-0000-0000-000000979402', 979401, 2,
                'USER', 'APP', 'A question of the previous release.', '00000000-0000-0000-0000-000000979403', now());
    ELSE
        INSERT INTO conversation_turn (id, workspace_id, conversation_id, request_id, status, surface, attempt, run_id,
                                       created_at, started_at, finished_at)
        VALUES ('00000000-0000-0000-0000-000000979403', 979400, '00000000-0000-0000-0000-000000979402',
                'cutover-run-owner', 'DONE', 'APP', 1, '00000000-0000-0000-0000-000000979406', now(), now(), now());
        INSERT INTO conversation_entry (id, workspace_id, conversation_id, seq, kind, surface, text, turn_id,
                                        created_at)
        VALUES ('00000000-0000-0000-0000-000000979404', 979400, '00000000-0000-0000-0000-000000979402', 1, 'ANSWER',
                'APP', 'An earlier answer.', NULL, now()),
               ('00000000-0000-0000-0000-000000979405', 979400, '00000000-0000-0000-0000-000000979402', 2, 'USER',
                'APP', 'A question of the previous release.', '00000000-0000-0000-0000-000000979403', now());
    END IF;
END
$$;
