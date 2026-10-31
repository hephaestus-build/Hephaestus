BEGIN;

INSERT INTO workspace (id, account_login, account_type, created_at, display_name, public_activity_enabled, slug, status,
  practices_enabled, practice_review_auto_trigger_enabled, practice_review_manual_trigger_enabled)
SELECT 1, 'hephaestustest', 'ORG', now(), 'E2E Practice Review', false, 'e2e', 'ACTIVE',
  true, true, true
WHERE NOT EXISTS (SELECT 1 FROM workspace WHERE id = 1);

INSERT INTO llm_connection (id, slug, display_name, base_url, api_protocol, auth_mode, enabled, created_at)
VALUES (1, 'e2e-gateway', 'E2E LLM Gateway', 'https://llm-gateway.example/api', 'openai-completions', 'BEARER', true, now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO llm_model (id, connection_id, slug, display_name, upstream_model_id, context_window, max_output_tokens, visibility, enabled, created_at)
VALUES (1, 1, 'claude-sonnet-4-5', 'Claude Sonnet 4.5', 'claude-sonnet-4-5', 200000, 64000, 'PUBLIC', true, now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO llm_model (id, connection_id, slug, display_name, upstream_model_id, context_window, max_output_tokens, visibility, enabled, created_at)
VALUES (2, 1, 'gpt-oss-120b', 'GPT-OSS 120B', 'openai/gpt-oss-120b', 128000, 32000, 'PUBLIC', true, now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO workspace_agent_binding (id, workspace_id, purpose, instance_model_id, enabled, timeout_seconds, max_concurrent_jobs, created_at, updated_at)
VALUES (1, 1, 'PRACTICE_REVIEW', 1, true, 600, 2, now(), now())
ON CONFLICT (id) DO UPDATE SET purpose = EXCLUDED.purpose;

INSERT INTO workspace_agent_binding (id, workspace_id, purpose, instance_model_id, enabled, timeout_seconds, max_concurrent_jobs, created_at, updated_at)
VALUES (2, 1, 'MENTOR', 2, true, 300, 1, now(), now())
ON CONFLICT (id) DO NOTHING;

-- The Hephaestus page offers Hephaestus once a model is ready under some AI choice, and a developer who has not chosen is
-- answered from the undeclared slot, which holds only a model with undeclared data handling. Model 3 declares
-- that it runs in house and fills the in-house slot.
INSERT INTO llm_model (id, connection_id, slug, display_name, upstream_model_id, context_window, max_output_tokens, visibility, enabled, operated_by, created_at)
VALUES (3, 1, 'in-house-model', 'In-house model', 'in-house-model', 128000, 32000, 'PUBLIC', true, 'OWN_ORGANISATION', now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO workspace_agent_binding (id, workspace_id, purpose, data_handling_tier, instance_model_id, enabled, timeout_seconds, max_concurrent_jobs, created_at, updated_at)
VALUES (3, 1, 'MENTOR', 'IN_HOUSE', 3, true, 300, 1, now(), now())
ON CONFLICT (id) DO NOTHING;

-- A model with no price never starts a turn, and Hephaestus reads as not set up without one.
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
INSERT INTO identity (account_id, provider_id, provider_user_key, linked_at, linked_via)
SELECT a.id, p.id, '900001', now(), 'SIGN_IN'
FROM account a
JOIN identity_provider p ON p.type = 'GITHUB' AND p.server_url = 'https://github.com'
WHERE a.primary_email = 'e2e@dev.invalid'
ON CONFLICT DO NOTHING;
INSERT INTO membership (workspace_id, account_id, role)
SELECT 1, id, 'ADMIN' FROM account WHERE primary_email = 'e2e@dev.invalid'
ON CONFLICT DO NOTHING;

-- One answered exchange in the e2e developer's conversation: `conversation.spec.ts` votes on the answer. The
-- question is older than anything a test asks, so every test's own messages are the newest.
INSERT INTO mentor_conversation (id, workspace_id, account_id, created_at, last_activity_at)
SELECT 'e2e0c0a0-0000-4000-8000-000000000001', 1, id, now() - interval '1 day', now() - interval '1 day'
FROM account WHERE primary_email = 'e2e@dev.invalid'
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

-- Isolated public activity: two published repositories, one private repository and one hidden person.
-- The source has no credentials or enabled streams. Use the playwright profile for this fixture.
INSERT INTO instance_settings (id, silent_mode_engaged, version, public_activity_allowed)
VALUES (1, true, 0, true)
ON CONFLICT (id) DO UPDATE SET public_activity_allowed = true;

INSERT INTO workspace (id, account_login, account_type, created_at, display_name, public_activity_enabled, slug, status,
  practices_enabled, practice_review_auto_trigger_enabled, practice_review_manual_trigger_enabled)
SELECT 9, 'e2e-public', 'ORG', now(), 'E2E Public Workspace', true, 'e2e-public', 'ACTIVE',
  false, false, false
WHERE NOT EXISTS (SELECT 1 FROM workspace WHERE id = 9);

INSERT INTO connection (workspace_id, kind, instance_key, state, config, created_at, updated_at, version)
VALUES (9, 'GITHUB', 'e2e-public', 'ACTIVE',
  '{"type":"GITHUB_PAT","orgLogin":"e2e-public","serverUrl":"https://github.com","enabledStreams":[]}'::jsonb,
  now(), now(), 0)
ON CONFLICT (workspace_id, kind, instance_key) DO NOTHING;

INSERT INTO "user" (id, native_id, provider_id, login, name, type, avatar_url, html_url, created_at, updated_at)
SELECT fixture.id, fixture.id, p.id, fixture.login, fixture.name, 'USER', '',
  'https://github.com/' || fixture.login, now(), now()
FROM identity_provider p
CROSS JOIN (VALUES
  (930001, 'e2e-public-river', 'E2E River'),
  (930002, 'e2e-public-hidden', 'E2E Hidden')
) AS fixture(id, login, name)
WHERE p.type = 'GITHUB' AND p.server_url = 'https://github.com'
ON CONFLICT (id) DO NOTHING;

-- Both chose to show on public pages: a member who has not answered is hidden, and E2E Hidden must
-- stay off the page because an admin hid them, not because of that default.
INSERT INTO account (id, display_name, status, created_at, updated_at, public_activity_visible)
VALUES (940001, 'E2E River', 'UNCLAIMED', now(), now(), true),
  (940002, 'E2E Hidden', 'UNCLAIMED', now(), now(), true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity (account_id, provider_id, provider_user_key, linked_via, linked_at)
SELECT fixture.account_id, p.id, fixture.provider_user_key, 'ROSTER', now()
FROM identity_provider p
CROSS JOIN (VALUES (940001, '930001'), (940002, '930002')) AS fixture(account_id, provider_user_key)
WHERE p.type = 'GITHUB' AND p.server_url = 'https://github.com'
ON CONFLICT DO NOTHING;

INSERT INTO membership (workspace_id, account_id, role, hidden, public_activity_seen)
VALUES (9, 940001, 'MEMBER', false, true), (9, 940002, 'MEMBER', true, true)
ON CONFLICT DO NOTHING;

INSERT INTO repository (id, native_id, provider_id, created_at, updated_at, html_url, is_archived, is_disabled,
  is_private, name, name_with_owner, visibility, has_discussions_enabled)
SELECT fixture.id, fixture.id, p.id, now(), now(), 'https://github.com/e2e-public/' || fixture.name,
  false, false, fixture.is_private, fixture.name, 'e2e-public/' || fixture.name, fixture.visibility, false
FROM identity_provider p
CROSS JOIN (VALUES
  (930001, 'api', false, 'PUBLIC'),
  (930002, 'web', false, 'PUBLIC'),
  (930003, 'private', true, 'PRIVATE')
) AS fixture(id, name, is_private, visibility)
WHERE p.type = 'GITHUB' AND p.server_url = 'https://github.com'
ON CONFLICT (id) DO NOTHING;

-- Public reads require a current visibility confirmation, not just a repository marked PUBLIC.
INSERT INTO repository_to_monitor (id, name_with_owner, workspace_id, native_id, generated_paths,
  repository_visibility_confirmed_at)
SELECT id, name_with_owner, 9, native_id, '[]'::jsonb, now()
FROM repository WHERE id IN (930001, 930002, 930003)
ON CONFLICT (id) DO UPDATE SET repository_visibility_confirmed_at = EXCLUDED.repository_visibility_confirmed_at;

INSERT INTO issue (issue_type, id, native_id, provider_id, created_at, updated_at, comments_count, html_url,
  is_locked, number, state, title, is_draft, is_merged, commits, additions, deletions, changed_files,
  author_id, repository_id, closed_at, merged_at, merged_by_id)
SELECT 'PULL_REQUEST', fixture.id, fixture.id, p.id, '2026-01-10T12:00:00Z', '2026-01-10T13:00:00Z', 0,
  fixture.url, false, fixture.number, fixture.state, fixture.title, false, fixture.merged, 1, 4, 0, 1,
  fixture.author, fixture.repository,
  CASE WHEN fixture.merged THEN '2026-01-10T13:00:00Z'::timestamptz END,
  CASE WHEN fixture.merged THEN '2026-01-10T13:00:00Z'::timestamptz END,
  CASE WHEN fixture.merged THEN fixture.author END
FROM identity_provider p
CROSS JOIN (VALUES
  (930011, 11, 'CLOSED', 'Document the public API', true, 930001, 930001,
    'https://github.com/e2e-public/api/pull/11'),
  (930012, 12, 'OPEN', 'Update the public web page', false, 930001, 930002,
    'https://github.com/e2e-public/web/pull/12'),
  (930013, 13, 'OPEN', 'Private repository work must stay private', false, 930001, 930003,
    'https://github.com/e2e-public/private/pull/13'),
  (930014, 14, 'OPEN', 'Hidden contributor work must stay private', false, 930002, 930001,
    'https://github.com/e2e-public/api/pull/14')
) AS fixture(id, number, state, title, merged, author, repository, url)
WHERE p.type = 'GITHUB' AND p.server_url = 'https://github.com'
ON CONFLICT (id) DO NOTHING;

INSERT INTO activity_event (id, event_key, event_type, occurred_at, actor_id, workspace_id, repository_id,
  target_type, target_id, ingested_at)
VALUES
  ('93000000-0000-4000-8000-000000000001', 'e2e-public:api:opened', 'PULL_REQUEST_OPENED',
    '2026-01-10T12:00:00Z', 930001, 9, 930001, 'pull_request', 930011, now()),
  ('93000000-0000-4000-8000-000000000002', 'e2e-public:api:merged', 'PULL_REQUEST_MERGED',
    '2026-01-10T13:00:00Z', 930001, 9, 930001, 'pull_request', 930011, now()),
  ('93000000-0000-4000-8000-000000000003', 'e2e-public:web:opened', 'PULL_REQUEST_OPENED',
    '2026-01-10T12:00:00Z', 930001, 9, 930002, 'pull_request', 930012, now()),
  ('93000000-0000-4000-8000-000000000004', 'e2e-public:private:opened', 'PULL_REQUEST_OPENED',
    '2026-01-10T12:00:00Z', 930001, 9, 930003, 'pull_request', 930013, now()),
  ('93000000-0000-4000-8000-000000000005', 'e2e-public:hidden:opened', 'PULL_REQUEST_OPENED',
    '2026-01-10T12:00:00Z', 930002, 9, 930001, 'pull_request', 930014, now())
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('account', 'id'), (SELECT max(id) FROM account), true);

COMMIT;
