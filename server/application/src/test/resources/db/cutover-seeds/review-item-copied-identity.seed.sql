-- Review items of the previous release that list their copied identities in detail.custody. The conversion copies each
-- distinct identity into review_item_copied_identity: two members with copies, one of them listing a copy twice and
-- one a Slack copy with its team, and a member with no custody.
INSERT INTO workspace (id, slug, display_name, account_login, account_type, status)
VALUES (979400, 'cutover-copied-identity', 'Cutover copied identity', 'cutover-copied-identity', 'ORG', 'ACTIVE');
INSERT INTO item (id, workspace_id, kind, source_id)
VALUES (979406, 979400, 'scm.pull_request', 79406),
       (979407, 979400, 'scm.pull_request', 79407),
       (979408, 979400, 'scm.pull_request', 79408);
INSERT INTO practice (id, workspace_id, slug, kind, current_version_id, feedback_mode)
VALUES (979404, 979400, 'cutover-copied-identity', 'scm.pull_request', 979405, 'AUTOMATIC');
INSERT INTO practice_version (id, workspace_id, practice_id, number, definition)
VALUES (979405, 979400, 979404, 1,
 '{"name":"Cutover copied identity","criteria":"Explain the purpose.","about":"AUTHOR",
   "reviewOn":{"events":["scm.pull_request.opened"],"state":{}},"appliesWhen":null,
   "reads":{"contract":"3.0.0","sources":[{"source":"scm.pull-request.core","need":"COMPLETE"}]},
   "reviewedBy":{"by":"HEPHAESTUS","humanReason":null,"limits":[]},"script":null,"scan":null,
   "lineNotes":false,"guidance":{"whyItMatters":null,"whatGoodLooksLike":null,"goingWell":null,"visual":null,"guide":null}}');
-- An item review lists only its own item, so each member has a review of its own.
INSERT INTO review (id, workspace_id, item_id, kind, item_version, origin, status, ordered_at)
VALUES ('00000000-0000-0000-0000-000000979411', 979400, 979406, 'ITEM', 'cutover-v1', 'REQUEST', 'QUEUED', now()),
       ('00000000-0000-0000-0000-000000979412', 979400, 979407, 'ITEM', 'cutover-v1', 'REQUEST', 'QUEUED', now()),
       ('00000000-0000-0000-0000-000000979413', 979400, 979408, 'ITEM', 'cutover-v1', 'REQUEST', 'QUEUED', now());
INSERT INTO review_practice (review_id, workspace_id, practice_id, practice_version_id, status)
VALUES ('00000000-0000-0000-0000-000000979411', 979400, 979404, 979405, 'READY'),
       ('00000000-0000-0000-0000-000000979412', 979400, 979404, 979405, 'READY'),
       ('00000000-0000-0000-0000-000000979413', 979400, 979404, 979405, 'READY');
INSERT INTO review_item (review_id, workspace_id, practice_id, item_id, item_version, detail)
VALUES ('00000000-0000-0000-0000-000000979411', 979400, 979404, 979406, 'cutover-v1',
        '{"custody":{"complete":true,"refused":false,"repositoryIds":[],"sourceJobIds":[],"copiedIdentities":[
            {"providerType":"GITHUB","providerOrigin":"https://github.com","subject":"7001","teamId":null},
            {"providerType":"GITHUB","providerOrigin":"https://github.com","subject":"7002","teamId":null},
            {"providerType":"GITHUB","providerOrigin":"https://github.com","subject":"7001","teamId":null}]}}'),
       ('00000000-0000-0000-0000-000000979412', 979400, 979404, 979407, 'cutover-v1',
        '{"custody":{"complete":true,"refused":false,"repositoryIds":[],"sourceJobIds":[],"copiedIdentities":[
            {"providerType":"SLACK","providerOrigin":"https://slack.com","subject":"U7003","teamId":"T7000"}]}}'),
       ('00000000-0000-0000-0000-000000979413', 979400, 979404, 979408, 'cutover-v1', NULL);
-- A release that already has the conversion keeps the copies only in their table, as its writers do.
DO $$
BEGIN
    IF to_regclass('public.review_item_copied_identity') IS NOT NULL THEN
        INSERT INTO review_item_copied_identity (workspace_id, review_id, practice_id, item_id, provider_type,
                                                 provider_origin, subject, team_id)
        VALUES (979400, '00000000-0000-0000-0000-000000979411', 979404, 979406, 'GITHUB', 'https://github.com',
                '7001', NULL),
               (979400, '00000000-0000-0000-0000-000000979411', 979404, 979406, 'GITHUB', 'https://github.com',
                '7002', NULL),
               (979400, '00000000-0000-0000-0000-000000979412', 979404, 979407, 'SLACK', 'https://slack.com',
                'U7003', 'T7000');
        UPDATE review_item SET detail = detail #- '{custody,copiedIdentities}' WHERE workspace_id = 979400;
    END IF;
END
$$;
