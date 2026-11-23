INSERT INTO public.workspace (id, slug, display_name, account_login, account_type, status)
VALUES (979124, 'conversation-owner-seed', 'Conversation owner seed', 'conversation-owner-seed', 'ORG', 'ACTIVE');
INSERT INTO public.account (id, display_name)
VALUES (979125, 'First conversation owner'), (979126, 'Second conversation owner');

INSERT INTO public.conversation (id, workspace_id, account_id, created_at, last_activity_at)
VALUES ('97912400-0000-4000-8000-000000000001', 979124, 979125, now(), now()),
       ('97912400-0000-4000-8000-000000000002', 979124, 979126, now(), now());
INSERT INTO public.agent_run (id, workspace_id, kind, band, concurrency_key, state, start_within_seconds)
VALUES ('97912400-0000-4000-8000-000000000031', 979124, 'TURN', 'INTERACTIVE',
        'conversation:97912400-0000-4000-8000-000000000001', 'QUEUED', 180),
       ('97912400-0000-4000-8000-000000000032', 979124, 'TURN', 'INTERACTIVE',
        'conversation:97912400-0000-4000-8000-000000000002', 'QUEUED', 180);
INSERT INTO public.conversation_turn (id, workspace_id, conversation_id, request_id, surface, status, created_at, run_id)
VALUES ('97912400-0000-4000-8000-000000000011', 979124, '97912400-0000-4000-8000-000000000001',
        'conversation-owner-seed-first', 'APP', 'QUEUED', now(), '97912400-0000-4000-8000-000000000031'),
       ('97912400-0000-4000-8000-000000000012', 979124, '97912400-0000-4000-8000-000000000002',
        'conversation-owner-seed-second', 'APP', 'QUEUED', now(), '97912400-0000-4000-8000-000000000032');
INSERT INTO public.conversation_entry (id, workspace_id, conversation_id, turn_id, seq, kind, surface, text, created_at)
VALUES ('97912400-0000-4000-8000-000000000021', 979124, '97912400-0000-4000-8000-000000000001',
        '97912400-0000-4000-8000-000000000011', 1, 'USER', 'APP', 'First question', now()),
       ('97912400-0000-4000-8000-000000000022', 979124, '97912400-0000-4000-8000-000000000002',
        '97912400-0000-4000-8000-000000000012', 1, 'USER', 'APP', 'Second question', now());
