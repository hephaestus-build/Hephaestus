-- The extension suite's workspace: a GitLab connection to the fixture origin and one monitored
-- project. Every id is fixed so the specs can name it:
--   item 920110: !4 "Add the login screen" by ext-author; item 920111: issue #4, sharing the number;
--   item 920112: !5 "Add the settings page", the second page of the same-tab navigation test;
--   item 920113: !6 "Tidy the README", by ext-developer. The `e2e-dev` account owns two provider
--     identities: a GitHub sign-in (920004) and, linked later, the GitLab ext-developer (920003). !6 is
--     authored by the second, so own work is resolved through any of an account's identities;
--   two reviews of !4 that never run: 7d5e0c3a-…-000000000001 is QUEUED with a far `available_at`, and
--     7d5e0c3a-…-000000000002 FAILED while processing. This suite neither cancels nor retries them.
--     Neither has output, and the fixture origin does not resolve;
--   workspace `ext-e2e-alternate` (21) monitors ext/choice with ext-e2e: issue #1 is item 922110 in
--     ext-e2e and item 922111 in ext-e2e-alternate.
-- Run after dev-login has created the `e2e` and `e2e-dev` accounts. Re-running resets both reviews.
BEGIN;

DO $$
BEGIN
  IF (SELECT count(*) FROM account WHERE primary_email IN ('e2e@dev.invalid', 'e2e-dev@dev.invalid')) <> 2 THEN
    RAISE EXCEPTION 'Create e2e and e2e-dev with dev-login before loading the extension seed';
  END IF;
END $$;

-- A fixture Git object id: the prefix e2e0 names a base commit and e2e1 a head commit.
CREATE FUNCTION pg_temp.object_id(prefix text, number integer) RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT prefix || lpad(number::text, 36, '0') $$;
-- The version the server derives for a pull request with no body and no closing work items
-- (ScmVersion.pullRequest): base, head and a length-prefixed digest of the title.
CREATE FUNCTION pg_temp.pull_request_version(number integer, title text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
  SELECT 'scm.pull_request:v1:' || pg_temp.object_id('e2e0', number) || ':' || pg_temp.object_id('e2e1', number) || ':'
    || encode(sha256('\x01'::bytea || int4send(octet_length(convert_to(title, 'UTF8'))) || convert_to(title, 'UTF8')
      || '\x00'::bytea), 'hex')
$$;

INSERT INTO identity_provider (type, server_url, created_at)
VALUES ('GITLAB', 'https://gitlab.example.test', now())
ON CONFLICT (type, server_url) DO NOTHING;

INSERT INTO workspace (id, account_login, account_type, created_at, display_name, public_activity_enabled, slug, status,
  practices_enabled, practice_review_auto_trigger_enabled, practice_review_manual_trigger_enabled)
SELECT w.* FROM (VALUES
  (20, 'ext', 'ORG', now(), 'Extension E2E', false, 'ext-e2e', 'ACTIVE', true, true, true),
  -- A separate repository is monitored by two workspaces, for frame-local workspace choice.
  -- The ordinary ext/demo work remains unambiguous for the other browser tests.
  (21, 'ext-choice', 'ORG', now(), 'Extension E2E alternate', false, 'ext-e2e-alternate', 'ACTIVE', true, false, false)
) AS w
-- The slug reservation trigger runs before ON CONFLICT could skip an existing workspace.
WHERE NOT EXISTS (SELECT 1 FROM workspace existing WHERE existing.id = w.column1);

INSERT INTO connection (workspace_id, kind, instance_key, state, config, created_at, updated_at, version)
SELECT workspace_id, 'GITLAB', 'https://gitlab.example.test:', 'ACTIVE',
  '{"type":"GITLAB","serverUrl":"https://gitlab.example.test","gitlabGroupId":null,"gitlabWebhookId":null,"signingMode":"PLAINTEXT","enabledStreams":[]}'::jsonb,
  now(), now(), 0
FROM (VALUES (20), (21)) AS workspaces(workspace_id)
ON CONFLICT (workspace_id, kind, instance_key) DO NOTHING;

INSERT INTO repository (id, created_at, updated_at, html_url, is_archived, is_disabled, is_private, name,
  name_with_owner, visibility, native_id, provider_id)
SELECT r.id, now(), now(), r.html_url, false, false, false, r.name, r.name_with_owner, 'PUBLIC', r.id, p.id
FROM (VALUES
  (920001, 'https://gitlab.example.test/ext/demo', 'demo', 'ext/demo'),
  (922001, 'https://gitlab.example.test/ext/choice', 'choice', 'ext/choice')
) AS r(id, html_url, name, name_with_owner)
JOIN identity_provider p ON p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

INSERT INTO repository_to_monitor (id, name_with_owner, workspace_id, native_id, generated_paths)
VALUES (920001, 'ext/demo', 20, 920001, '[]'::jsonb), (922001, 'ext/choice', 20, 922001, '[]'::jsonb),
  (922002, 'ext/choice', 21, 922001, '[]'::jsonb)
ON CONFLICT (id) DO NOTHING;

-- Provider users. ext-developer and the GitHub e2e-dev are the `e2e-dev` account's two identities.
INSERT INTO "user" (id, native_id, provider_id, login, name, type, avatar_url, html_url, created_at, updated_at)
SELECT u.id, u.id, p.id, u.login, u.name, 'USER', '', u.html_url, now(), now()
FROM (VALUES
  (920002, 'GITLAB', 'https://gitlab.example.test', 'ext-author', 'Ext Author', 'https://gitlab.example.test/ext-author'),
  (920003, 'GITLAB', 'https://gitlab.example.test', 'ext-developer', 'Ext Developer', 'https://gitlab.example.test/ext-developer'),
  (920004, 'GITHUB', 'https://github.com', 'e2e-dev', 'E2E Developer', 'https://github.com/e2e-dev')
) AS u(id, provider_type, server_url, login, name, html_url)
JOIN identity_provider p ON p.type = u.provider_type AND p.server_url = u.server_url
ON CONFLICT (id) DO NOTHING;

-- Pull requests carry the refs their version is derived from; without them the work does not resolve.
INSERT INTO issue (issue_type, id, created_at, updated_at, comments_count, html_url, is_locked, number, state, title,
  is_draft, is_merged, commits, additions, deletions, changed_files, base_ref_name, base_ref_oid, head_ref_name,
  head_ref_oid, author_id, repository_id, native_id, provider_id)
SELECT 'PULL_REQUEST', w.id, now(), now(), 0, 'https://gitlab.example.test/ext/demo/-/merge_requests/' || w.number, false,
  w.number, 'OPEN', w.title, false, false, 1, w.additions, w.deletions, w.changed_files, 'main',
  pg_temp.object_id('e2e0', w.number), 'e2e-' || w.number, pg_temp.object_id('e2e1', w.number), w.author_id, 920001,
  w.id, p.id
FROM (VALUES
  (920010, 4, 'Add the login screen', 12, 3, 2, 920002),
  (920012, 5, 'Add the settings page', 8, 1, 1, 920002),
  (920013, 6, 'Tidy the README', 2, 2, 1, 920003)
) AS w(id, number, title, additions, deletions, changed_files, author_id)
JOIN identity_provider p ON p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

INSERT INTO issue (issue_type, id, created_at, updated_at, comments_count, html_url, is_locked, number, state, title,
  author_id, repository_id, native_id, provider_id)
SELECT 'ISSUE', w.id, now(), now(), 0, w.html_url, false, w.number, 'OPEN', w.title, 920002, w.repository_id, w.id, p.id
FROM (VALUES
  (920011, 'https://gitlab.example.test/ext/demo/-/issues/4', 4, 'Login screen is missing', 920001),
  (922010, 'https://gitlab.example.test/ext/choice/-/issues/1', 1, 'Shared workspace context', 922001)
) AS w(id, html_url, number, title, repository_id)
JOIN identity_provider p ON p.type = 'GITLAB' AND p.server_url = 'https://gitlab.example.test'
ON CONFLICT (id) DO NOTHING;

-- ext-author has never signed in: a roster account, so another developer's feedback has an owner.
INSERT INTO account (id, display_name, status, created_at, updated_at)
VALUES (920002, 'Ext Author', 'UNCLAIMED', now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity (id, account_id, provider_id, provider_user_key, linked_via, linked_at)
SELECT i.id, a.id, p.id, i.provider_user_key, i.linked_via, i.linked_at
FROM (VALUES
  (920002, 'ext-author', 'GITLAB', 'https://gitlab.example.test', '920002', 'ROSTER', timestamptz '2026-09-01 08:00:00+00'),
  (920004, 'e2e-dev', 'GITHUB', 'https://github.com', '920004', 'SIGN_IN', timestamptz '2026-09-01 08:00:00+00'),
  (920003, 'e2e-dev', 'GITLAB', 'https://gitlab.example.test', '920003', 'SIGN_IN', timestamptz '2026-09-02 08:00:00+00')
) AS i(id, owner, provider_type, server_url, provider_user_key, linked_via, linked_at)
JOIN account a ON (i.owner = 'ext-author' AND a.id = 920002) OR a.primary_email = i.owner || '@dev.invalid'
JOIN identity_provider p ON p.type = i.provider_type AND p.server_url = i.server_url
ON CONFLICT DO NOTHING;

-- `e2e` administers both workspaces; `e2e-dev` is a member who is not an admin.
INSERT INTO membership (workspace_id, account_id, role)
SELECT m.workspace_id, a.id, m.role
FROM (VALUES (20, 'e2e', 'ADMIN'), (21, 'e2e', 'ADMIN'), (20, 'e2e-dev', 'MEMBER'), (20, 'ext-author', 'MEMBER'))
  AS m(workspace_id, owner, role)
JOIN account a ON (m.owner = 'ext-author' AND a.id = 920002) OR a.primary_email = m.owner || '@dev.invalid'
ON CONFLICT DO NOTHING;

-- Items: each workspace has its own rows for the same provider work.
INSERT INTO item (id, workspace_id, kind, parent_id, source_id)
VALUES
  (920100, 20, 'scm.repository', NULL, 920001),
  (920110, 20, 'scm.pull_request', 920100, 920010),
  (920111, 20, 'scm.issue', 920100, 920011),
  (920112, 20, 'scm.pull_request', 920100, 920012),
  (920113, 20, 'scm.pull_request', 920100, 920013),
  (922100, 20, 'scm.repository', NULL, 922001),
  (922110, 20, 'scm.issue', 922100, 922010),
  (922101, 21, 'scm.repository', NULL, 922001),
  (922111, 21, 'scm.issue', 922101, 922010)
ON CONFLICT DO NOTHING;

-- Synthetic practices. They name no event, so no provider event starts a review of them.
INSERT INTO practice (id, workspace_id, slug, kind, current_version_id, feedback_mode, created_at)
SELECT d.id, 20, d.slug, 'scm.pull_request', d.id, 'OFF', timestamptz '2026-09-27 08:00:00+00'
FROM (VALUES (923101, 'e2e-explain-validation'), (923102, 'e2e-keep-changes-focused'),
  (923103, 'e2e-unposted-proposal')) AS d(id, slug)
ON CONFLICT (id) DO NOTHING;
INSERT INTO practice_version (id, workspace_id, practice_id, number, definition, created_at)
SELECT d.id, 20, d.id, 1, jsonb_build_object(
    'name', d.name,
    'reviewOn', '{"events":[],"state":{}}'::jsonb,
    'appliesWhen', NULL, 'about', 'AUTHOR',
    'reads', '{"contract":"2.0.0","sources":[{"source":"scm.pull-request.diff","need":"REQUIRED"}]}'::jsonb,
    'reviewedBy', '{"by":"HEPHAESTUS","humanReason":null,"limits":[]}'::jsonb,
    'criteria', 'Synthetic extension fixture only. No model reviews this practice.',
    'script', NULL, 'scan', NULL, 'lineNotes', true,
    'guidance', '{"whyItMatters":null,"whatGoodLooksLike":null,"goingWell":null,"visual":null,"guide":null}'::jsonb),
  timestamptz '2026-09-27 08:00:00+00'
FROM (VALUES (923101, 'Explain validation'), (923102, 'Keep changes focused'),
  (923103, 'E2E_UNPOSTED_PROPOSAL_PRACTICE')) AS d(id, name)
ON CONFLICT (id) DO NOTHING;

-- !4's history: an event withheld for want of a model, and two reviews that never run.
INSERT INTO item_event (id, workspace_id, item_id, name, item_version, occurred_at, found_by, state, reason, decided_at)
VALUES
  ('6f1c2a90-2b7e-4c1d-9a8f-0e1d2c3b4a59', 20, 920110, 'scm.pull_request.opened',
   pg_temp.pull_request_version(4, 'Add the login screen'), now() - interval '30 minutes', 'WEBHOOK', 'WITHHELD',
   'REVIEW_MODEL_UNBOUND', now() - interval '30 minutes'),
  ('6f1c2a90-2b7e-4c1d-9a8f-0e1d2c3b4a61', 20, 920110, 'scm.pull_request.ready',
   pg_temp.pull_request_version(4, 'Add the login screen'), now() - interval '2 hours', 'WEBHOOK', 'STARTED', NULL,
   now() - interval '2 hours')
ON CONFLICT (id) DO NOTHING;

INSERT INTO review (id, workspace_id, item_id, item_version, base_version, origin, event_id, requested_by_id, status,
  stop_reason, ordered_at, created_at, admitted_at, completed_at)
SELECT r.id, 20, 920110, pg_temp.pull_request_version(4, 'Add the login screen'), pg_temp.object_id('e2e0', 4), r.origin,
  r.event_id, CASE WHEN r.origin = 'REQUEST' THEN a.id END, r.status, r.stop_reason, r.at, r.at,
  CASE WHEN r.status = 'FAILED' THEN r.at END, CASE WHEN r.status = 'FAILED' THEN r.at + interval '10 minutes' END
FROM (VALUES
  ('7d5e0c3a-1b2c-4d3e-8f40-000000000001'::uuid, 'REQUEST', NULL::uuid, 'QUEUED', NULL, now() - interval '5 minutes'),
  ('7d5e0c3a-1b2c-4d3e-8f40-000000000002'::uuid, 'EVENT', '6f1c2a90-2b7e-4c1d-9a8f-0e1d2c3b4a61'::uuid, 'FAILED',
   'PROCESSING_FAILED', now() - interval '2 hours')
) AS r(id, origin, event_id, status, stop_reason, at)
JOIN account a ON a.primary_email = 'e2e@dev.invalid'
ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, stop_reason = EXCLUDED.stop_reason,
  admitted_at = EXCLUDED.admitted_at, completed_at = EXCLUDED.completed_at;

INSERT INTO review_practice (review_id, practice_id, workspace_id, practice_version_id, status, reason)
VALUES ('7d5e0c3a-1b2c-4d3e-8f40-000000000001', 923101, 20, 923101, 'QUEUED', NULL),
  ('7d5e0c3a-1b2c-4d3e-8f40-000000000002', 923101, 20, 923101, 'FAILED', 'PROCESSING_FAILED')
ON CONFLICT (review_id, practice_id) DO UPDATE SET status = EXCLUDED.status, reason = EXCLUDED.reason;

INSERT INTO review_job (id, review_id, workspace_id, status, attempt, processing_status, available_at, trace_id, input,
  config_snapshot, submitted_at, started_at, completed_at)
SELECT j.id, j.review_id, 20, j.status, j.attempt, j.processing, j.available_at, replace(j.id::text, '-', ''),
  jsonb_build_object('itemId', 920110, 'assignments',
    jsonb_build_array(jsonb_build_object('reviewId', j.review_id, 'practiceId', 923101, 'itemId', 920110))),
  '{}'::jsonb, r.created_at, j.started_at, j.completed_at
FROM (VALUES
  ('7d5e0c3a-1b2c-4d3e-8f41-000000000001'::uuid, '7d5e0c3a-1b2c-4d3e-8f40-000000000001'::uuid, 'QUEUED', 0, 'NONE',
   now() + interval '7 days', NULL::timestamptz, NULL::timestamptz),
  ('7d5e0c3a-1b2c-4d3e-8f41-000000000002'::uuid, '7d5e0c3a-1b2c-4d3e-8f40-000000000002'::uuid, 'COMPLETED', 1, 'FAILED',
   now() - interval '2 hours', now() - interval '2 hours', now() - interval '110 minutes')
) AS j(id, review_id, status, attempt, processing, available_at, started_at, completed_at)
JOIN review r ON r.id = j.review_id
ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, processing_status = EXCLUDED.processing_status,
  available_at = EXCLUDED.available_at, completed_at = EXCLUDED.completed_at, cancellation_reason = NULL;

INSERT INTO review_item (workspace_id, review_id, practice_id, item_id, item_version, state, job_id)
SELECT 20, j.review_id, 923101, 920110, pg_temp.pull_request_version(4, 'Add the login screen'), 'LISTED', j.job_id
FROM (VALUES
  ('7d5e0c3a-1b2c-4d3e-8f40-000000000001'::uuid, '7d5e0c3a-1b2c-4d3e-8f41-000000000001'::uuid),
  ('7d5e0c3a-1b2c-4d3e-8f40-000000000002'::uuid, '7d5e0c3a-1b2c-4d3e-8f41-000000000002'::uuid)
) AS j(review_id, job_id)
ON CONFLICT DO NOTHING;

-- Recorded on-work comment scenarios, confined to this disposable fixture origin. These rows simulate
-- provider outcomes; no model ran, nothing was posted and no send exists. One completed review per
-- work, for the reader of that work: `e2e` on !4 and `e2e-dev` on !6. Feedback k has id
-- 8f1e2026-000N-4b00-8000-00000000000k, where N is the work's number:
--   1  posted summary copy (note N001) and a separate line-note copy, README.md:12 (note N002);
--   2  a second feedback whose summary copy is the same provider comment (note N001);
--   3  a partially failed package: only its README.md:18 line note (note N003) was posted. It rests on
--      no result, so no practice is named for it; the failed copy keeps its private path and body;
--   4  another account's feedback, with convincing provider refs;
--   5  practice-page feedback and 6 conversation feedback, both with convincing refs;
--   7  failed feedback with no posted copy;
--   8  replaced feedback (feedback 1 replaces it), with a convincing ref.
-- So the reader has exactly three provider comments. The three results are about the reader: result 1 is
-- not met with one cited problem, results 2 and 3 are met. Feedback 1 rests on result 1 and feedback 2 on
-- result 2. Comment URLs are fixture anchors, never addresses of real external comments.
DO $$
DECLARE
  target record;
  f record;
  reader bigint;
  other bigint;
  this_review uuid;
  this_job uuid;
  this_feedback uuid;
  this_result uuid;
  version text;
  page_url text;
  note_base bigint;
  citation jsonb := '{"sourceKind":"scm.pull-request.diff","sourcePath":"work/change/diff.patch","path":"README.md","side":"NEW","commit":null,"startLine":12,"endLine":12,"quote":"Synthetic line","verification":null}'::jsonb;
  fixture_time timestamptz := '2026-09-27 08:00:00+00';
BEGIN
  SELECT id INTO other FROM account WHERE id = 920002;
  FOR target IN SELECT * FROM (VALUES (4, 920110, 'Add the login screen', 'e2e@dev.invalid'),
      (6, 920113, 'Tidy the README', 'e2e-dev@dev.invalid')) AS targets(number, item_id, title, email) LOOP
    SELECT id INTO reader FROM account WHERE primary_email = target.email;
    this_review := format('8f1e2026-%s-4a00-8000-000000000001', lpad(target.number::text, 4, '0'))::uuid;
    this_job := format('8f1e2026-%s-4a10-8000-000000000001', lpad(target.number::text, 4, '0'))::uuid;
    version := pg_temp.pull_request_version(target.number, target.title);
    page_url := format('https://gitlab.example.test/ext/demo/-/merge_requests/%s', target.number);
    note_base := 920000 + target.number * 1000;

    INSERT INTO review (id, workspace_id, item_id, item_version, base_version, origin, requested_by_id, status,
      ordered_at, created_at, admitted_at, completed_at)
    VALUES (this_review, 20, target.item_id, version, pg_temp.object_id('e2e0', target.number), 'REQUEST', reader,
      'COMPLETE', fixture_time, fixture_time, fixture_time, fixture_time + interval '1 minute')
    ON CONFLICT (id) DO NOTHING;
    INSERT INTO review_job (id, review_id, workspace_id, status, attempt, processing_status, available_at, trace_id,
      input, config_snapshot, submitted_at, started_at, completed_at)
    VALUES (this_job, this_review, 20, 'COMPLETED', 1, 'DONE', fixture_time, replace(this_job::text, '-', ''),
      jsonb_build_object('itemId', target.item_id, 'assignments', (SELECT jsonb_agg(jsonb_build_object(
        'reviewId', this_review, 'practiceId', p, 'itemId', target.item_id) ORDER BY p)
        FROM unnest(ARRAY[923101, 923102, 923103]) AS p)),
      '{"syntheticFixture":true}'::jsonb, fixture_time, fixture_time, fixture_time + interval '1 minute')
    ON CONFLICT (id) DO NOTHING;

    FOR f IN SELECT * FROM (VALUES (1, 'NOT_MET'), (2, 'MET'), (3, 'MET')) AS results(ordinal, outcome) LOOP
      this_result := format('8f1e2026-%s-4d00-8000-%s', lpad(target.number::text, 4, '0'),
        lpad(f.ordinal::text, 12, '0'))::uuid;
      INSERT INTO review_practice (review_id, practice_id, workspace_id, practice_version_id, status)
      VALUES (this_review, 923100 + f.ordinal, 20, 923100 + f.ordinal, 'DONE')
      ON CONFLICT DO NOTHING;
      -- The assignment exists before its result, then records it; custody is retained on it.
      INSERT INTO review_item (workspace_id, review_id, practice_id, item_id, item_version, state, job_id,
        attempted_at, detail)
      VALUES (20, this_review, 923100 + f.ordinal, target.item_id, version, 'LISTED', this_job, fixture_time,
        '{"custody":{"complete":true,"copiedIdentities":[],"repositoryIds":[],"sourceJobIds":[]}}'::jsonb)
      ON CONFLICT DO NOTHING;
      INSERT INTO result (id, workspace_id, review_id, practice_id, practice_version_id, item_id, item_version,
        developer_id, outcome, summary, explanation, evidence, coverage, recorded_at)
      VALUES (this_result, 20, this_review, 923100 + f.ordinal, 923100 + f.ordinal, target.item_id, version, reader,
        f.outcome, 'Synthetic browser-test result; no model reviewed this work.',
        'Synthetic explanation for deterministic browser validation only.',
        jsonb_build_object('citations', CASE WHEN f.ordinal IN (1, 2) THEN jsonb_build_array(citation)
          ELSE '[]'::jsonb END, 'openQuestions', '[]'::jsonb),
        '{"source":"claimed","complete":true,"selected":1,"checked":1,"total":1}'::jsonb, fixture_time)
      ON CONFLICT (id) DO NOTHING;
      -- A not met result has at least one problem; the check runs when the transaction commits.
      IF f.outcome = 'NOT_MET' THEN
        INSERT INTO problem (id, workspace_id, result_id, position, severity, summary, explanation, citations, basis)
        VALUES (format('8f1e2026-%s-4e00-8000-000000000001', lpad(target.number::text, 4, '0'))::uuid, 20,
          this_result, 0, 'MAJOR', 'Synthetic browser-test problem; no model reviewed this work.',
          'Synthetic explanation for deterministic browser validation only.', jsonb_build_array(citation),
          '{"type":"duty","criterionQuote":"Synthetic extension fixture only.","event":0,"explanation":"Synthetic basis."}'::jsonb)
        ON CONFLICT (id) DO NOTHING;
      END IF;
      UPDATE review_item SET state = 'JUDGED', result_id = this_result
      WHERE review_item.review_id = this_review AND practice_id = 923100 + f.ordinal AND item_id = target.item_id
        AND state = 'LISTED';
    END LOOP;

    -- Feedback 8 first: feedback 1 replaces it.
    FOR f IN SELECT * FROM (VALUES
      (8, 'ON_WORK', 'REPLACED', false, 'E2E_REPLACED_SUMMARY_BODY'),
      (1, 'ON_WORK', 'DELIVERED', false, 'E2E_POSTED_SUMMARY_BODY'),
      (2, 'ON_WORK', 'DELIVERED', false, 'E2E_SHARED_SUMMARY_BODY'),
      (3, 'ON_WORK', 'DELIVERED', false, 'E2E_PARTIAL_PACKAGE_BODY'),
      (4, 'ON_WORK', 'DELIVERED', true, 'E2E_OTHER_ACCOUNT_BODY'),
      (5, 'PRACTICE_PAGE', 'DELIVERED', false, 'E2E_PRIVATE_PRACTICE_PAGE_BODY'),
      (6, 'CONVERSATION', 'DELIVERED', false, 'E2E_PRIVATE_CONVERSATION_BODY'),
      (7, 'ON_WORK', 'FAILED', false, 'E2E_UNPOSTED_FAILED_BODY')
    ) AS feedback(ordinal, destination, state, other_account, body) LOOP
      this_feedback := format('8f1e2026-%s-4b00-8000-%s', lpad(target.number::text, 4, '0'),
        lpad(f.ordinal::text, 12, '0'))::uuid;
      INSERT INTO feedback (id, workspace_id, review_id, item_id, developer_id, destination, state, body,
        replaces_id, created_at, delivered_at)
      VALUES (this_feedback, 20, this_review,
        CASE WHEN f.destination = 'PRACTICE_PAGE' THEN NULL ELSE target.item_id END,
        CASE WHEN f.other_account THEN other ELSE reader END, f.destination, f.state,
        f.body || '_MR' || target.number,
        CASE WHEN f.ordinal = 1 THEN format('8f1e2026-%s-4b00-8000-000000000008',
          lpad(target.number::text, 4, '0'))::uuid END,
        fixture_time + f.ordinal * interval '1 minute',
        CASE WHEN f.state = 'FAILED' THEN NULL ELSE fixture_time + f.ordinal * interval '1 minute' END)
      ON CONFLICT (id) DO NOTHING;
      IF f.ordinal IN (1, 2) THEN
        INSERT INTO feedback_basis (id, workspace_id, feedback_id, result_id, developer_id, ordinal)
        VALUES (format('8f1e2026-%s-4f00-8000-%s', lpad(target.number::text, 4, '0'),
            lpad(f.ordinal::text, 12, '0'))::uuid, 20, this_feedback,
          format('8f1e2026-%s-4d00-8000-%s', lpad(target.number::text, 4, '0'),
            lpad(f.ordinal::text, 12, '0'))::uuid, reader, 0)
        ON CONFLICT DO NOTHING;
      END IF;
    END LOOP;

    -- Copy p of feedback k has id 8f1e2026-000N-4c0p-8000-00000000000k.
    INSERT INTO feedback_copy (id, workspace_id, feedback_id, kind, position, body, path, start_line, end_line, side,
      state, last_error, external_ref, external_url, created_at, posted_at)
    SELECT format('8f1e2026-%s-4c0%s-8000-%s', lpad(target.number::text, 4, '0'), c.position,
        lpad(c.ordinal::text, 12, '0'))::uuid, 20,
      format('8f1e2026-%s-4b00-8000-%s', lpad(target.number::text, 4, '0'), lpad(c.ordinal::text, 12, '0'))::uuid,
      c.kind, c.position, c.body, c.path, c.line, c.line, CASE WHEN c.path IS NULL THEN NULL ELSE 'NEW' END, c.state,
      CASE WHEN c.state = 'FAILED' THEN 'Synthetic provider refusal.' END,
      CASE WHEN c.note IS NULL THEN NULL ELSE 'gid://gitlab/Note/' || (note_base + c.note) END,
      CASE WHEN c.note IS NULL THEN NULL ELSE page_url || '#note_' || (note_base + c.note) END,
      fixture_time + c.ordinal * interval '1 minute',
      CASE WHEN c.state = 'POSTED' THEN fixture_time + c.ordinal * interval '1 minute' END
    FROM (VALUES
      (1, 0, 'SUMMARY', NULL, NULL, NULL::integer, 'POSTED', 1),
      (1, 1, 'LINE_NOTE', 'E2E_POSTED_LINE_NOTE_BODY', 'README.md', 12, 'POSTED', 2),
      (2, 0, 'SUMMARY', NULL, NULL, NULL, 'POSTED', 1),
      (3, 0, 'LINE_NOTE', 'E2E_PARTIAL_LINE_NOTE_BODY', 'README.md', 18, 'POSTED', 3),
      (3, 1, 'LINE_NOTE', 'E2E_UNPOSTED_PRIVATE_COPY_BODY', 'E2E_UNPOSTED_PRIVATE_PATH.md', 99, 'FAILED', NULL),
      (4, 0, 'SUMMARY', NULL, NULL, NULL, 'POSTED', 4),
      (5, 0, 'SUMMARY', NULL, NULL, NULL, 'POSTED', 5),
      (6, 0, 'CHAT_TURN', 'E2E_PRIVATE_CONVERSATION_TURN_BODY', NULL, NULL, 'POSTED', 6),
      (7, 0, 'SUMMARY', NULL, NULL, NULL, 'FAILED', NULL),
      (8, 0, 'SUMMARY', NULL, NULL, NULL, 'POSTED', 8)
    ) AS c(ordinal, position, kind, body, path, line, state, note)
    ON CONFLICT (id) DO NOTHING;
  END LOOP;
END $$;

SELECT setval(pg_get_serial_sequence('workspace', 'id'), GREATEST(21, (SELECT max(id) FROM workspace)), true);
SELECT setval(pg_get_serial_sequence('account', 'id'), (SELECT max(id) FROM account), true);
SELECT setval(pg_get_serial_sequence('identity', 'id'), (SELECT max(id) FROM identity), true);
SELECT setval(pg_get_serial_sequence('item', 'id'), (SELECT max(id) FROM item), true);
SELECT setval(pg_get_serial_sequence('practice', 'id'), (SELECT max(id) FROM practice), true);
SELECT setval(pg_get_serial_sequence('practice_version', 'id'), (SELECT max(id) FROM practice_version), true);

COMMIT;
