BEGIN;

INSERT INTO instance_settings (id, silent_mode_engaged, version)
VALUES (1, TRUE, 0)
ON CONFLICT (id) DO NOTHING;

UPDATE instance_settings
SET silent_mode_engaged = TRUE,
    silent_mode_reason = 'Restore clone: pre-boot safety lock',
    silent_mode_changed_at = now(),
    silent_mode_changed_by = 'restore-operator',
    version = version + 1
WHERE id = 1;

UPDATE workspace
SET practice_delivery_status = 'PAUSED',
    practice_rollout_revision = practice_rollout_revision + 1,
    practice_config_version = practice_config_version + 1,
    practice_review_auto_trigger_enabled = FALSE,
    practice_review_manual_trigger_enabled = FALSE,
    mentor_enabled = FALSE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM instance_settings WHERE id = 1 AND NOT silent_mode_engaged)
        OR EXISTS (SELECT 1 FROM workspace WHERE practice_delivery_status <> 'PAUSED' OR mentor_enabled)
    THEN
        RAISE EXCEPTION 'restore clone lockdown did not engage';
    END IF;
END
$$;

COMMIT;
