-- A conversation of the previous release with one entry. The seed runs on every release that an upgrade can start from:
-- the 1.0 baseline (v1.0.0-rc.1) names the tables mentor_*, later releases conversation_*, and a release that already
-- has the conversion stores the entry's account itself. Turns change shape across these releases, so the migration test
-- covers them.
INSERT INTO workspace (id, slug, display_name, account_login, account_type, status)
VALUES (979300, 'cutover-row-owner', 'Cutover row owner', 'cutover-row-owner', 'ORG', 'ACTIVE');
INSERT INTO account (id, display_name) VALUES (979301, 'Cutover owner');
DO $$
BEGIN
    IF to_regclass('public.mentor_conversation') IS NOT NULL THEN
        INSERT INTO mentor_conversation (id, workspace_id, account_id, created_at, last_activity_at)
        VALUES ('00000000-0000-0000-0000-000000979302', 979300, 979301, now(), now());
        INSERT INTO mentor_entry (id, workspace_id, conversation_id, seq, kind, surface, text, created_at)
        VALUES ('00000000-0000-0000-0000-000000979305', 979300, '00000000-0000-0000-0000-000000979302', 1, 'USER',
                'APP', 'A question from the previous release.', now());
    ELSIF EXISTS (SELECT FROM information_schema.columns
                  WHERE table_schema = 'public' AND table_name = 'conversation_entry' AND column_name = 'account_id') THEN
        INSERT INTO conversation (id, workspace_id, account_id, created_at, last_activity_at)
        VALUES ('00000000-0000-0000-0000-000000979302', 979300, 979301, now(), now());
        INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface, text,
                                        created_at)
        VALUES ('00000000-0000-0000-0000-000000979305', 979300, '00000000-0000-0000-0000-000000979302', 979301, 1,
                'USER', 'APP', 'A question from the previous release.', now());
    ELSE
        INSERT INTO conversation (id, workspace_id, account_id, created_at, last_activity_at)
        VALUES ('00000000-0000-0000-0000-000000979302', 979300, 979301, now(), now());
        INSERT INTO conversation_entry (id, workspace_id, conversation_id, seq, kind, surface, text, created_at)
        VALUES ('00000000-0000-0000-0000-000000979305', 979300, '00000000-0000-0000-0000-000000979302', 1, 'USER',
                'APP', 'A question from the previous release.', now());
    END IF;
END
$$;
