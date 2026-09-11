BEGIN;

INSERT INTO workspace (id, account_login, account_type, created_at, display_name, is_publicly_viewable, slug, status,
  practices_enabled, leaderboard_enabled, progression_enabled, leagues_enabled,
  practice_review_auto_trigger_enabled, practice_review_manual_trigger_enabled, mentor_enabled)
VALUES (1, 'hephaestustest', 'ORG', now(), 'E2E Practice Review', false, 'e2e', 'ACTIVE',
  true, false, false, false, true, true, true)
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

INSERT INTO workspace_agent_binding (id, workspace_id, purpose, instance_model_id, enabled, timeout_seconds, max_concurrent_jobs, allow_internet, created_at, updated_at)
VALUES (1, 1, 'PRACTICE_REVIEW', 1, true, 600, 2, false, now(), now())
ON CONFLICT (id) DO UPDATE SET purpose = EXCLUDED.purpose;

INSERT INTO workspace_agent_binding (id, workspace_id, purpose, instance_model_id, enabled, timeout_seconds, max_concurrent_jobs, allow_internet, created_at, updated_at)
VALUES (2, 1, 'MENTOR', 2, true, 1200, 1, true, now(), now())
ON CONFLICT (id) DO NOTHING;

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
INSERT INTO workspace_membership (workspace_id, user_id, role, league_points, hidden, created_at)
VALUES (1, 900001, 'ADMIN', 0, false, now())
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('llm_connection', 'id'), 10, true);
SELECT setval(pg_get_serial_sequence('llm_model', 'id'), 10, true);
SELECT setval(pg_get_serial_sequence('workspace_agent_binding', 'id'), 10, true);
SELECT setval(pg_get_serial_sequence('workspace', 'id'), 10, true);

COMMIT;
