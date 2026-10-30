-- TEST ONLY. Execute only in the isolated capacity-bench release's database, after dev-login.
BEGIN;
DO $$ BEGIN
    IF EXISTS (SELECT FROM workspace WHERE id <> 986600)
        OR EXISTS (SELECT FROM account WHERE display_name !~ '^capacity-bench-[0-9]{3}$') THEN
        RAISE EXCEPTION 'Refusing a database with non-synthetic workspaces or accounts';
    END IF;
END $$;
INSERT INTO workspace (id, account_login, account_type, display_name, slug, status, public_activity_enabled, practice_delivery_status)
SELECT 986600, 'capacity-bench', 'ORG', 'Synthetic capacity course', 'capacity-bench-course', 'ACTIVE', false, 'PAUSED'
WHERE NOT EXISTS (SELECT FROM workspace WHERE id = 986600)
ON CONFLICT DO NOTHING;
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM workspace WHERE id = 986600 AND slug = 'capacity-bench-course' AND account_login = 'capacity-bench' AND practice_delivery_status = 'PAUSED') THEN
        RAISE EXCEPTION 'Synthetic workspace missing';
    END IF;
END $$;
INSERT INTO identity_provider (type, server_url)
VALUES ('GITLAB', 'http://127.0.0.1:18080')
ON CONFLICT (type, server_url) DO NOTHING;
INSERT INTO membership (workspace_id, account_id, role, hidden, review_selected, onboarding_seen_revision, public_activity_seen, joined_at)
SELECT 986600, id, CASE WHEN app_role = 'APP_ADMIN' THEN 'OWNER' ELSE 'MEMBER' END, false, true, 0, false, now()
FROM account WHERE display_name ~ '^capacity-bench-[0-9]{3}$'
ON CONFLICT DO NOTHING;
UPDATE account SET ai_choice = 'CLOUD', ai_choice_at = now()
WHERE display_name ~ '^capacity-bench-[0-9]{3}$';
INSERT INTO "user" (created_at, updated_at, native_id, provider_id, login, name, type, avatar_url, html_url)
SELECT now(), now(), a.id, p.id, a.display_name, a.display_name, 'USER', '', 'http://127.0.0.1:18080/synthetic'
FROM account a CROSS JOIN identity_provider p
WHERE a.display_name ~ '^capacity-bench-[0-9]{3}$' AND p.type = 'GITLAB' AND p.server_url = 'http://127.0.0.1:18080'
ON CONFLICT DO NOTHING;
INSERT INTO identity (account_id, provider_id, provider_user_key, team_key, linked_via, linked_at, last_login_at)
SELECT a.id, p.id, a.id::text, '', 'SIGN_IN', now(), now()
FROM account a CROSS JOIN identity_provider p
WHERE a.display_name ~ '^capacity-bench-[0-9]{3}$' AND p.type = 'GITLAB' AND p.server_url = 'http://127.0.0.1:18080'
ON CONFLICT DO NOTHING;
SELECT json_build_object('synthetic_members', count(*)) FROM membership WHERE workspace_id=986600;
COMMIT;
