-- Each member keeps exactly its distinct copied identities in review_item_copied_identity, no detail lists them any
-- more, and a member with no custody has none.
DO $$
BEGIN
    IF EXISTS (
        WITH expected (workspace_id, review_id, practice_id, item_id, provider_type, provider_origin, subject,
                       team_id) AS (
            VALUES (979400::bigint, '00000000-0000-0000-0000-000000979411'::uuid, 979404::bigint, 979406::bigint,
                    'GITHUB'::varchar, 'https://github.com'::varchar, '7001'::varchar, NULL::varchar),
                   (979400, '00000000-0000-0000-0000-000000979411'::uuid, 979404, 979406,
                    'GITHUB', 'https://github.com', '7002', NULL),
                   (979400, '00000000-0000-0000-0000-000000979412'::uuid, 979404, 979407,
                    'SLACK', 'https://slack.com', 'U7003', 'T7000')),
        actual AS (
            SELECT workspace_id, review_id, practice_id, item_id, provider_type, provider_origin, subject, team_id
            FROM review_item_copied_identity WHERE workspace_id = 979400)
        (SELECT * FROM actual EXCEPT ALL SELECT * FROM expected)
        UNION ALL
        (SELECT * FROM expected EXCEPT ALL SELECT * FROM actual)) THEN
        RAISE EXCEPTION 'The copied identities of the seeded review items differ from the expected ones';
    END IF;
    IF EXISTS (SELECT FROM review_item
               WHERE workspace_id = 979400 AND detail->'custody'->'copiedIdentities' IS NOT NULL) THEN
        RAISE EXCEPTION 'A review item still lists its copied identities in its detail';
    END IF;
END
$$;
