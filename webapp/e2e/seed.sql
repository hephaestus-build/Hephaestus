BEGIN;

INSERT INTO workspace (id, account_login, account_type, created_at, display_name, public_activity_enabled, slug, status,
  practices_enabled, practice_review_auto_trigger_enabled, practice_review_manual_trigger_enabled)
VALUES (1, 'hephaestustest', 'ORG', now(), 'E2E Practice Review', false, 'e2e', 'ACTIVE',
  true, true, true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO llm_connection (id, slug, display_name, base_url, api_protocol, auth_mode, enabled, created_at)
VALUES (1, 'e2e-gateway', 'E2E LLM Gateway', 'https://llm-gateway.example/api', 'openai-completions', 'BEARER', true, now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO llm_model (id, connection_id, slug, display_name, upstream_model_id, context_window, max_output_tokens, supports_reasoning, visibility, enabled, created_at)
VALUES (1, 1, 'claude-sonnet-4-5', 'Claude Sonnet 4.5', 'claude-sonnet-4-5', 200000, 64000, false, 'PUBLIC', true, now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO llm_model (id, connection_id, slug, display_name, upstream_model_id, context_window, max_output_tokens, supports_reasoning, visibility, enabled, created_at)
VALUES (2, 1, 'gpt-oss-120b', 'GPT-OSS 120B', 'openai/gpt-oss-120b', 128000, 32000, true, 'PUBLIC', true, now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO workspace_agent_binding (id, workspace_id, purpose, instance_model_id, enabled, timeout_seconds, max_concurrent_jobs, created_at, updated_at)
VALUES (1, 1, 'PRACTICE_REVIEW', 1, true, 600, 2, now(), now())
ON CONFLICT (id) DO UPDATE SET purpose = EXCLUDED.purpose;

INSERT INTO workspace_agent_binding (id, workspace_id, purpose, instance_model_id, enabled, timeout_seconds, max_concurrent_jobs, created_at, updated_at)
VALUES (2, 1, 'MENTOR', 2, true, 1200, 1, now(), now())
ON CONFLICT (id) DO NOTHING;

-- The Heph page offers Heph once a model is ready under some AI choice, and a developer who has not chosen is
-- answered from the undeclared slot, which holds only a model with undeclared data handling. Model 3 declares
-- that it runs in house and fills the in-house slot.
INSERT INTO llm_model (id, connection_id, slug, display_name, upstream_model_id, context_window, max_output_tokens, supports_reasoning, visibility, enabled, operated_by, created_at)
VALUES (3, 1, 'in-house-model', 'In-house model', 'in-house-model', 128000, 32000, false, 'PUBLIC', true, 'OWN_ORGANISATION', now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO workspace_agent_binding (id, workspace_id, purpose, data_handling_tier, instance_model_id, enabled, timeout_seconds, max_concurrent_jobs, created_at, updated_at)
VALUES (3, 1, 'MENTOR', 'IN_HOUSE', 3, true, 1200, 1, now(), now())
ON CONFLICT (id) DO NOTHING;

-- A model with no price never starts a turn, and Heph reads as not set up without one.
INSERT INTO llm_model_price (model_id, pricing_mode, note, effective_from, created_at)
SELECT id, 'NO_CHARGE', 'E2E fixture model', now() - interval '1 day', now()
FROM llm_model m
WHERE m.id IN (1, 2, 3) AND NOT EXISTS (SELECT 1 FROM llm_model_price p WHERE p.model_id = m.id);

INSERT INTO "user" (id, native_id, provider_id, login, type, avatar_url, html_url, created_at, updated_at)
SELECT 900001, 900001, id, 'e2e', 'USER', '', 'https://github.com/e2e', now(), now()
FROM identity_provider
WHERE type = 'GITHUB' AND server_url = 'https://github.com'
ON CONFLICT (id) DO NOTHING;
-- Resolve the account created by dev-login without assuming its generated ID.
INSERT INTO identity_link (id, account_id, provider_id, subject, linked_at, linked_via, external_actor_id, username_at_signup)
SELECT 1, a.id, p.id, '900001', now(), 'OAUTH_LOGIN', 900001, 'e2e'
FROM account a
JOIN identity_provider p ON p.type = 'GITHUB' AND p.server_url = 'https://github.com'
WHERE a.primary_email = 'e2e@dev.invalid'
ON CONFLICT (id) DO NOTHING;
INSERT INTO workspace_membership (workspace_id, user_id, role, hidden, created_at)
VALUES (1, 900001, 'ADMIN', false, now())
ON CONFLICT DO NOTHING;

-- One answered exchange in the e2e developer's Heph conversation: `heph.spec.ts` votes on the answer. The
-- question is older than anything a test asks, so every test's own messages are the newest.
INSERT INTO mentor_conversation (id, workspace_id, user_id, created_at, last_activity_at)
VALUES ('e2e0c0a0-0000-4000-8000-000000000001', 1, 900001, now() - interval '1 day', now() - interval '1 day')
ON CONFLICT DO NOTHING;
INSERT INTO mentor_turn (id, workspace_id, conversation_id, request_id, status, surface, created_at, started_at, finished_at)
VALUES ('e2e0c0a0-0000-4000-8000-000000000004', 1, 'e2e0c0a0-0000-4000-8000-000000000001',
        'e2e0c0a0-0000-4000-8000-000000000005', 'DONE', 'APP', now() - interval '1 day',
        now() - interval '1 day', now() - interval '1 day')
ON CONFLICT DO NOTHING;
INSERT INTO mentor_entry (id, workspace_id, conversation_id, seq, kind, surface, text, turn_id, created_at)
VALUES ('e2e0c0a0-0000-4000-8000-000000000002', 1, 'e2e0c0a0-0000-4000-8000-000000000001', 1, 'USER', 'APP',
        'How do I keep my pull requests small?', 'e2e0c0a0-0000-4000-8000-000000000004', now() - interval '1 day'),
       ('e2e0c0a0-0000-4000-8000-000000000003', 1, 'e2e0c0a0-0000-4000-8000-000000000001', 2, 'HEPH', 'APP',
        'Split the work by behaviour: one pull request per change a reviewer can judge on its own.',
        'e2e0c0a0-0000-4000-8000-000000000004', now() - interval '1 day')
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('llm_connection', 'id'), 10, true);
SELECT setval(pg_get_serial_sequence('llm_model', 'id'), 10, true);
SELECT setval(pg_get_serial_sequence('workspace_agent_binding', 'id'), 10, true);
SELECT setval(pg_get_serial_sequence('workspace', 'id'), 10, true);

SELECT setval(pg_get_serial_sequence('identity_link', 'id'), (SELECT max(id) FROM identity_link), true);

COMMIT;
