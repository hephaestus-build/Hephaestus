-- TEST ONLY. Execute in bench-postgresql in capacity-bench-20261030, after receipt export and owner cleanup.
BEGIN;
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM workspace WHERE id = 986600 AND slug = 'capacity-bench-course'
        AND account_login = 'capacity-bench' AND practice_delivery_status = 'PAUSED') THEN
        RAISE EXCEPTION 'Synthetic workspace guard failed';
    END IF;
    IF EXISTS (SELECT FROM membership m JOIN account a ON a.id = m.account_id
        WHERE m.workspace_id = 986600 AND a.display_name !~ '^capacity-bench-[0-9]{3}$') THEN
        RAISE EXCEPTION 'Non-synthetic workspace member';
    END IF;
    IF EXISTS (SELECT FROM mentor_turn WHERE workspace_id = 986600 AND status IN ('QUEUED', 'RUNNING')) THEN
        RAISE EXCEPTION 'Synthetic turns still active';
    END IF;
END $$;
DELETE FROM mentor_conversation WHERE workspace_id = 986600;
COMMIT;
