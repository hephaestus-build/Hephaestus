-- The seeded entry carries its conversation's account.
DO $$
BEGIN
    IF (SELECT account_id FROM conversation_entry WHERE id = '00000000-0000-0000-0000-000000979305')
            IS DISTINCT FROM 979301 THEN
        RAISE EXCEPTION 'The seeded conversation entry does not carry its conversation''s account';
    END IF;
END
$$;
