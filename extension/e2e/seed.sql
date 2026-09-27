-- The extension suite's workspace: a GitLab connection to the fixture origin and one monitored
-- project with
--   !4 "Add the login screen" (by ext-author) and issue #4, sharing the number;
--   !5 "Add the settings page", the second page of the same-tab navigation test;
--   !6 "Tidy the README", by ext-developer, the linked developer who is not an admin;
--   two reviews of !4 that never run: one QUEUED with a far `available_at`, and one COMPLETED whose
--   result processing FAILED. This suite neither cancels nor retries them. Neither has output, and the
--   fixture origin does not resolve.
-- Run after dev-login has created the `e2e` and `e2e-dev` accounts and after `webapp/e2e/seed.sql`,
-- which creates the `e2e` developer (user 900001). Re-running resets both reviews.
BEGIN;

DO $$
BEGIN
  IF (SELECT count(*) FROM account WHERE primary_email IN ('e2e@dev.invalid', 'e2e-dev@dev.invalid')) <> 2 THEN
    RAISE EXCEPTION 'Create e2e and e2e-dev with dev-login before loading the extension seed';
  END IF;
END $$;

INSERT INTO identity_provider (type, server_url, created_at)
VALUES ('GITLAB', 'https://gitlab.example.test', now())
ON CONFLICT (type, server_url) DO NOTHING;

INSERT INTO workspace (id, account_login, account_type, created_at, display_name, is_publicly_viewable, slug, status,
  practices_enabled, leaderboard_enabled, progression_enabled, leagues_enabled,
  practice_review_auto_trigger_enabled, practice_review_manual_trigger_enabled, mentor_enabled)
VALUES (20, 'ext', 'ORG', now(), 'Extension E2E', false, 'ext-e2e', 'ACTIVE',
  true, false, false, false, true, true, false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO connection (workspace_id, kind, instance_key, state, config, created_at, updated_at, version)
VALUES (20, 'GITLAB', 'https://gitlab.example.test:', 'ACTIVE',
  '{"type":"GITLAB","serverUrl":"https://gitlab.example.test","gitlabGroupId":null,"gitlabWebhookId":null,"signingMode":"PLAINTEXT","enabledStreams":[]}'::jsonb,
  now(), now(), 0)
ON CONFLICT (workspace_id, kind, instance_key) DO NOTHING;

INSERT INTO repository (id, created_at, updated_at, html_url, is_archived, is_disabled, is_private, name,
  name_with_owner, visibility, has_discussions_enabled, native_id, provider_id)
SELECT 920001, now(), now(), 'https://gitlab.example.test/ext/demo', false, false, false, 'demo',
  'ext/demo', 'PUBLIC', false, 920001, p.id
FROM identity_provider p WHERE p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

INSERT INTO repository_to_monitor (id, name_with_owner, workspace_id, native_id)
VALUES (920001, 'ext/demo', 20, 920001)
ON CONFLICT (id) DO NOTHING;

INSERT INTO "user" (id, native_id, provider_id, login, name, type, avatar_url, html_url, created_at, updated_at)
SELECT 920002, 920002, p.id, 'ext-author', 'Ext Author', 'USER', '', 'https://gitlab.example.test/ext-author', now(), now()
FROM identity_provider p WHERE p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

INSERT INTO issue (issue_type, id, created_at, updated_at, comments_count, html_url, is_locked, number, state, title,
  is_draft, is_merged, commits, additions, deletions, changed_files, author_id, repository_id, native_id, provider_id)
SELECT 'PULL_REQUEST', 920010, now(), now(), 0, 'https://gitlab.example.test/ext/demo/-/merge_requests/4', false, 4,
  'OPEN', 'Add the login screen', false, false, 1, 12, 3, 2, 920002, 920001, 920010, p.id
FROM identity_provider p WHERE p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

INSERT INTO issue (issue_type, id, created_at, updated_at, comments_count, html_url, is_locked, number, state, title,
  author_id, repository_id, native_id, provider_id)
SELECT 'ISSUE', 920011, now(), now(), 0, 'https://gitlab.example.test/ext/demo/-/issues/4', false, 4,
  'OPEN', 'Login screen is missing', 920002, 920001, 920011, p.id
FROM identity_provider p WHERE p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

INSERT INTO artifact_signal (id, workspace_id, artifact_kind, artifact_id, signal_name, revision, occurred_at,
  discovered_via, state, state_reason, state_changed_at)
VALUES ('6f1c2a90-2b7e-4c1d-9a8f-0e1d2c3b4a59', 20, 'scm.pull_request', 920010, 'scm.pull_request.ready', 'e2e0001',
  now() - interval '30 minutes', 'EVENT', 'SUPPRESSED', 'REVIEW_MODEL_UNBOUND', now() - interval '30 minutes')
ON CONFLICT (id) DO NOTHING;

INSERT INTO workspace_membership (workspace_id, user_id, role, league_points, hidden, created_at)
VALUES (20, 900001, 'ADMIN', 0, false, now())
ON CONFLICT DO NOTHING;

INSERT INTO issue (issue_type, id, created_at, updated_at, comments_count, html_url, is_locked, number, state, title,
  is_draft, is_merged, commits, additions, deletions, changed_files, author_id, repository_id, native_id, provider_id)
SELECT 'PULL_REQUEST', 920012, now(), now(), 0, 'https://gitlab.example.test/ext/demo/-/merge_requests/5', false, 5,
  'OPEN', 'Add the settings page', false, false, 1, 8, 1, 1, 920002, 920001, 920012, p.id
FROM identity_provider p WHERE p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

-- The developer who is not an admin: a GitLab identity linked to the `e2e-dev` account.
INSERT INTO "user" (id, native_id, provider_id, login, name, type, avatar_url, html_url, created_at, updated_at)
SELECT 920003, 920003, p.id, 'ext-developer', 'Ext Developer', 'USER', '', 'https://gitlab.example.test/ext-developer', now(), now()
FROM identity_provider p WHERE p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity_link (id, account_id, provider_id, subject, linked_at, linked_via, external_actor_id, username_at_signup)
SELECT 920003, a.id, p.id, '920003', now(), 'OAUTH_LOGIN', 920003, 'ext-developer'
FROM account a
JOIN identity_provider p ON p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
WHERE a.primary_email = 'e2e-dev@dev.invalid'
ON CONFLICT (id) DO NOTHING;

INSERT INTO workspace_membership (workspace_id, user_id, role, league_points, hidden, created_at)
VALUES (20, 920003, 'MEMBER', 0, false, now())
ON CONFLICT DO NOTHING;

INSERT INTO issue (issue_type, id, created_at, updated_at, comments_count, html_url, is_locked, number, state, title,
  is_draft, is_merged, commits, additions, deletions, changed_files, author_id, repository_id, native_id, provider_id)
SELECT 'PULL_REQUEST', 920013, now(), now(), 0, 'https://gitlab.example.test/ext/demo/-/merge_requests/6', false, 6,
  'OPEN', 'Tidy the README', false, false, 1, 2, 2, 1, 920003, 920001, 920013, p.id
FROM identity_provider p WHERE p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

-- Two reviews of !4 that never run.
INSERT INTO agent_job (id, workspace_id, job_type, status, metadata, config_snapshot, job_token, retry_count,
  created_at, started_at, completed_at, delivery_status, integration_kind, artifact_kind, available_at,
  delivery_attempts, practice_rollout_revision, practice_trigger_mode, trace_id)
VALUES
  ('7d5e0c3a-1b2c-4d3e-8f40-000000000001', 20, 'PULL_REQUEST_REVIEW', 'QUEUED',
   '{"pull_request_id":920010,"pr_number":4,"title":"Add the login screen","repository_full_name":"ext/demo"}'::jsonb,
   '{}'::jsonb, 'e2e-extension-queued-token', 0, now() - interval '5 minutes', NULL, NULL, NULL, 'GITLAB',
   'scm.pull_request', now() + interval '7 days', 0, 0, 'MANUAL', 'e2eextensionqueued00000000000001'),
  ('7d5e0c3a-1b2c-4d3e-8f40-000000000002', 20, 'PULL_REQUEST_REVIEW', 'COMPLETED',
   '{"pull_request_id":920010,"pr_number":4,"title":"Add the login screen","repository_full_name":"ext/demo"}'::jsonb,
   '{}'::jsonb, 'e2e-extension-failed-token', 0, now() - interval '2 hours', now() - interval '2 hours',
   now() - interval '110 minutes', 'FAILED', 'GITLAB', 'scm.pull_request', now() - interval '2 hours', 1, 0,
   'MANUAL', 'e2eextensionfailed00000000000002')
ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, delivery_status = EXCLUDED.delivery_status,
  available_at = EXCLUDED.available_at, completed_at = EXCLUDED.completed_at, cancellation_reason = NULL;

INSERT INTO artifact_signal (id, workspace_id, artifact_kind, artifact_id, signal_name, revision, occurred_at,
  discovered_via, state, job_id, state_changed_at)
VALUES
  ('6f1c2a90-2b7e-4c1d-9a8f-0e1d2c3b4a60', 20, 'scm.pull_request', 920010, 'scm.pull_request.ready', 'e2e0002',
   now() - interval '5 minutes', 'MANUAL', 'TRIGGERED', '7d5e0c3a-1b2c-4d3e-8f40-000000000001', now() - interval '5 minutes'),
  ('6f1c2a90-2b7e-4c1d-9a8f-0e1d2c3b4a61', 20, 'scm.pull_request', 920010, 'scm.pull_request.ready', 'e2e0003',
   now() - interval '2 hours', 'EVENT', 'TRIGGERED', '7d5e0c3a-1b2c-4d3e-8f40-000000000002', now() - interval '2 hours')
ON CONFLICT (id) DO NOTHING;

-- A separate repository is monitored by two workspaces, for frame-local workspace choice.
-- The ordinary ext/demo work remains unambiguous for the other browser tests.
INSERT INTO workspace (id, account_login, account_type, created_at, display_name, is_publicly_viewable, slug, status,
  practices_enabled, leaderboard_enabled, progression_enabled, leagues_enabled,
  practice_review_auto_trigger_enabled, practice_review_manual_trigger_enabled, mentor_enabled)
VALUES (21, 'ext-choice', 'ORG', now(), 'Extension E2E alternate', false, 'ext-e2e-alternate', 'ACTIVE',
  true, false, false, false, false, false, false)
ON CONFLICT (id) DO NOTHING;
INSERT INTO connection (workspace_id, kind, instance_key, state, config, created_at, updated_at, version)
VALUES (21, 'GITLAB', 'https://gitlab.example.test:', 'ACTIVE',
  '{"type":"GITLAB","serverUrl":"https://gitlab.example.test","gitlabGroupId":null,"gitlabWebhookId":null,"signingMode":"PLAINTEXT","enabledStreams":[]}'::jsonb,
  now(), now(), 0)
ON CONFLICT (workspace_id, kind, instance_key) DO NOTHING;
INSERT INTO workspace_membership (workspace_id, user_id, role, league_points, hidden, created_at)
VALUES (21, 900001, 'ADMIN', 0, false, now()) ON CONFLICT DO NOTHING;
INSERT INTO repository (id, created_at, updated_at, html_url, is_archived, is_disabled, is_private, name,
  name_with_owner, visibility, has_discussions_enabled, native_id, provider_id)
SELECT 922001, now(), now(), 'https://gitlab.example.test/ext/choice', false, false, false, 'choice',
  'ext/choice', 'PUBLIC', false, 922001, p.id
FROM identity_provider p WHERE p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;
INSERT INTO repository_to_monitor (id, name_with_owner, workspace_id, native_id)
VALUES (922001, 'ext/choice', 20, 922001), (922002, 'ext/choice', 21, 922001)
ON CONFLICT (id) DO NOTHING;
INSERT INTO issue (issue_type, id, created_at, updated_at, comments_count, html_url, is_locked, number, state, title,
  author_id, repository_id, native_id, provider_id)
SELECT 'ISSUE', 922010, now(), now(), 0, 'https://gitlab.example.test/ext/choice/-/issues/1', false, 1,
  'OPEN', 'Shared workspace context', 920002, 922001, 922010, p.id
FROM identity_provider p WHERE p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

-- Recorded native-comment scenarios, confined to this disposable fixture origin. These rows simulate
-- provider outcomes; no model, provider post, dispatch or outbox event is created. Each account sees
-- only its own three units: two share a summary and one records a successful part of a failed package.
-- Comment URLs are deterministic fixture anchors, never addresses of real external comments.
DO $$
DECLARE
  target record;
  definition record;
  item record;
  run_id uuid;
  feedback_id uuid;
  placement_id uuid;
  observation_id uuid;
  page_url text;
  comment_base bigint;
  fixture_time timestamptz := '2026-09-27 08:00:00+00';
  policy jsonb := '{"sourceContractVersion":"1.2.0","automatedReview":{"mode":"NONE","evidenceSufficiency":"NONE"},"whenEvidenceIsInsufficient":"SKIP_AUTOMATED_REVIEW","knownLimitations":[],"insufficiencyReason":null}'::jsonb;
BEGIN
  FOR definition IN SELECT * FROM (VALUES
    (923101, 'e2e-explain-validation', 'Explain validation'),
    (923102, 'e2e-keep-changes-focused', 'Keep changes focused'),
    (923103, 'e2e-unposted-proposal', 'E2E_UNPOSTED_PROPOSAL_PRACTICE')
  ) AS definitions(id, slug, name) LOOP
    INSERT INTO practice(id, workspace_id, slug, name, bindings, criteria, created_at, updated_at,
      applies_to, automated_review_policy, delivery_behavior, autonomy)
    VALUES (definition.id, 20, definition.slug, definition.name,
      '[{"signals":["scm.pull_request.extension_fixture"],"needs":[],"subject":"AUTHOR","onDrafts":false}]'::jsonb,
      'Synthetic extension fixture only. This practice never schedules a review.', fixture_time, fixture_time,
      'scm.pull_request', policy, '{"summaryOnly":false}'::jsonb, 'OFF')
    ON CONFLICT (id) DO NOTHING;
    INSERT INTO practice_revision(id, practice_id, revision_number, criteria, created_at, slug, name,
      applies_to, bindings, automated_review_policy, delivery_behavior)
    SELECT id, id, 1, criteria, fixture_time, slug, name, applies_to, bindings,
      automated_review_policy, delivery_behavior FROM practice WHERE id = definition.id
    ON CONFLICT (id) DO NOTHING;
    UPDATE practice SET current_revision_id = definition.id
      WHERE id = definition.id AND current_revision_id IS DISTINCT FROM definition.id;
  END LOOP;

  FOR target IN SELECT * FROM (VALUES (4, 920010, 900001), (6, 920013, 920003))
      AS targets(number, artifact_id, recipient_id) LOOP
    run_id := format('8f1e2026-%s-4a00-8000-000000000001', lpad(target.number::text, 4, '0'))::uuid;
    page_url := format('https://gitlab.example.test/ext/demo/-/merge_requests/%s', target.number);
    comment_base := 920000 + target.number * 1000;
    INSERT INTO agent_job(id, workspace_id, job_type, status, metadata, config_snapshot, job_token,
      retry_count, created_at, started_at, completed_at, delivery_status, integration_kind,
      artifact_kind, available_at, delivery_attempts, practice_rollout_revision, practice_trigger_mode,
      trace_id, in_chat_prepared_at, in_app_prepared_at)
    VALUES(run_id, 20, 'PULL_REQUEST_REVIEW', 'COMPLETED',
      jsonb_build_object('pull_request_id', target.artifact_id, 'pr_number', target.number,
        'pr_url', page_url, 'title', 'Synthetic native-comment fixture', 'repository_full_name', 'ext/demo',
        'synthetic_fixture', true),
      '{"synthetic_fixture":true,"modelExecuted":false,"externalDeliveryAllowed":false}'::jsonb,
      format('extension-native-feedback-never-execute-%s', target.number), 999,
      fixture_time, fixture_time, fixture_time + interval '1 minute', 'DELIVERED', 'GITLAB',
      'scm.pull_request', fixture_time, 32767, 0, 'MANUAL', replace(run_id::text, '-', ''),
      fixture_time, fixture_time)
    ON CONFLICT(id) DO NOTHING;

    FOR item IN SELECT * FROM (VALUES
      (1, 'IN_CONTEXT', 'DELIVERED', false, 'E2E_POSTED_SUMMARY_BODY'),
      (2, 'IN_CONTEXT', 'DELIVERED', false, 'E2E_SHARED_SUMMARY_BODY'),
      (3, 'IN_CONTEXT', 'PARTIALLY_FAILED', false, 'E2E_UNPOSTED_DRAFT_BODY'),
      (4, 'IN_CONTEXT', 'DELIVERED', true, 'E2E_OTHER_RECIPIENT_BODY'),
      (5, 'IN_APP', 'DELIVERED', false, 'E2E_PRIVATE_IN_APP_BODY'),
      (6, 'IN_CHAT', 'DELIVERED', false, 'E2E_PRIVATE_IN_CHAT_BODY'),
      (7, 'IN_CONTEXT', 'FAILED', false, 'E2E_UNPOSTED_FAILED_BODY'),
      (8, 'IN_CONTEXT', 'SUPERSEDED', false, 'E2E_REPLACED_SUMMARY_BODY')
    ) AS items(ordinal, channel, state, other_recipient, body) LOOP
      feedback_id := format('8f1e2026-%s-4b00-8000-%s', lpad(target.number::text, 4, '0'),
        lpad(item.ordinal::text, 12, '0'))::uuid;
      INSERT INTO feedback(id, agent_job_id, workspace_id, artifact_kind, artifact_id,
        recipient_user_id, about_user_id, channel, position, delivery_state, body, source,
        created_at, delivered_at, proposed_placements, proposed_practice_slugs)
      VALUES(feedback_id, run_id, 20,
        CASE WHEN item.channel = 'IN_APP' THEN NULL ELSE 'scm.pull_request' END,
        CASE WHEN item.channel = 'IN_APP' THEN NULL ELSE target.artifact_id END,
        CASE WHEN item.other_recipient THEN 920002 ELSE target.recipient_id END,
        CASE WHEN item.other_recipient THEN 920002 ELSE target.recipient_id END,
        item.channel, item.ordinal, item.state, item.body || '_MR' || target.number, 'AGENT',
        fixture_time + item.ordinal * interval '1 minute',
        CASE WHEN item.state = 'DELIVERED' THEN fixture_time + item.ordinal * interval '1 minute' ELSE NULL END,
        CASE WHEN item.ordinal = 3 THEN
          '[{"type":"INLINE","anchorKind":"LINE","path":"E2E_UNPOSTED_PRIVATE_PATH.md","startLine":99,"endLine":null,"side":"NEW","body":"E2E_UNPOSTED_PRIVATE_PLACEMENT_BODY","deliveryKey":"e2e-not-delivered"}]'::jsonb
          ELSE '[]'::jsonb END,
        CASE WHEN item.ordinal = 3 THEN '["e2e-unposted-proposal"]'::jsonb ELSE '[]'::jsonb END)
      ON CONFLICT(id) DO NOTHING;

      -- A failed unit with no successful placement must not appear. Other-recipient/private and
      -- replaced rows deliberately carry convincing recorded refs: those refs do not grant access.
      IF item.ordinal <> 7 THEN
        placement_id := format('8f1e2026-%s-4c00-8000-%s', lpad(target.number::text, 4, '0'),
          lpad(item.ordinal::text, 12, '0'))::uuid;
        INSERT INTO feedback_placement(id, feedback_id, placement_type, anchor_kind, anchor_path,
          anchor_start_line, anchor_end_line, anchor_side, posted_comment_ref, posted_comment_url, created_at)
        VALUES(placement_id, feedback_id, CASE WHEN item.ordinal = 3 THEN 'INLINE' ELSE 'SUMMARY' END,
          CASE WHEN item.ordinal = 3 THEN 'LINE' ELSE NULL END,
          CASE WHEN item.ordinal = 3 THEN 'README.md' ELSE NULL END,
          CASE WHEN item.ordinal = 3 THEN 18 ELSE NULL END,
          CASE WHEN item.ordinal = 3 THEN 18 ELSE NULL END,
          CASE WHEN item.ordinal = 3 THEN 'NEW' ELSE NULL END,
          'gid://gitlab/Note/' || (comment_base + CASE WHEN item.ordinal = 2 THEN 1 ELSE item.ordinal END),
          page_url || '#note_' || (comment_base + CASE WHEN item.ordinal = 2 THEN 1 ELSE item.ordinal END),
          fixture_time + item.ordinal * interval '1 minute')
        ON CONFLICT(id) DO NOTHING;
      END IF;

      IF item.ordinal <= 3 THEN
        observation_id := format('8f1e2026-%s-4d00-8000-%s', lpad(target.number::text, 4, '0'),
          lpad(item.ordinal::text, 12, '0'))::uuid;
        INSERT INTO observation(id, occurrence_key, agent_job_id, workspace_id, practice_id,
          practice_revision_id, artifact_kind, artifact_id, about_user_id, summary,
          assessment_status, presence, assessment, severity, origin, observed_at, evidence_rationale)
        VALUES(observation_id, 'extension-native-feedback-' || target.number || '-' || item.ordinal,
          run_id, 20, 923100 + item.ordinal, 923100 + item.ordinal, 'scm.pull_request', target.artifact_id,
          target.recipient_id, 'Synthetic browser-test observation; no model reviewed this work.',
          'ASSESSED', 'PRESENT', 'GOOD', NULL, 'MANUAL', fixture_time,
          'Synthetic evidence for deterministic browser validation only.')
        ON CONFLICT(id) DO NOTHING;
        INSERT INTO feedback_observation(feedback_id, observation_id, role, ordinal)
        VALUES(feedback_id, observation_id, 'PRIMARY', 0) ON CONFLICT DO NOTHING;
      END IF;
    END LOOP;

    -- The first delivered unit also has a distinct line comment; together with the shared summary
    -- and partial unit this makes four returned placements but exactly three native comments.
    INSERT INTO feedback_placement(id, feedback_id, placement_type, anchor_kind, anchor_path,
      anchor_start_line, anchor_end_line, anchor_side, posted_comment_ref, posted_comment_url, created_at)
    VALUES(format('8f1e2026-%s-4c00-8000-000000000009', lpad(target.number::text, 4, '0'))::uuid,
      format('8f1e2026-%s-4b00-8000-000000000001', lpad(target.number::text, 4, '0'))::uuid,
      'INLINE', 'LINE', 'README.md', 12, 12, 'NEW', 'gid://gitlab/Note/' || (comment_base + 2),
      page_url || '#note_' || (comment_base + 2), fixture_time + interval '1 minute')
    ON CONFLICT(id) DO NOTHING;
  END LOOP;
END $$;

SELECT setval(pg_get_serial_sequence('practice', 'id'), (SELECT max(id) FROM practice), true);
SELECT setval(pg_get_serial_sequence('practice_revision', 'id'), (SELECT max(id) FROM practice_revision), true);

SELECT setval(pg_get_serial_sequence('workspace', 'id'), GREATEST(20, (SELECT max(id) FROM workspace)), true);

SELECT setval(pg_get_serial_sequence('identity_link', 'id'), (SELECT max(id) FROM identity_link), true);

COMMIT;
