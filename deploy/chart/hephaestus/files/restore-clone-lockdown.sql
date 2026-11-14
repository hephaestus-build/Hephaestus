BEGIN;

INSERT INTO instance_settings (id, silent_mode_engaged, version)
VALUES (1, TRUE, 0)
ON CONFLICT (id) DO NOTHING;

UPDATE instance_settings
SET silent_mode_engaged = TRUE,
    silent_mode_reason = 'Restore clone: pre-boot safety lock',
    silent_mode_changed_at = now(),
    silent_mode_changed_by_account_id = NULL,
    version = version + 1
WHERE id = 1;

UPDATE workspace
SET practice_delivery_status = 'PAUSED',
    practice_rollout_revision = practice_rollout_revision + 1,
    practice_config_version = practice_config_version + 1,
    practice_review_event_enabled = FALSE,
    practice_review_request_enabled = FALSE;

-- Hephaestus answers wherever a conversation model binding is enabled; disable every one, and an admin re-enables them under AI models.
UPDATE workspace_model_assignment
SET enabled = FALSE,
    updated_at = now()
WHERE purpose = 'CONVERSATION';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM instance_settings WHERE id = 1 AND NOT silent_mode_engaged)
        OR EXISTS (SELECT 1 FROM workspace WHERE practice_delivery_status <> 'PAUSED')
        OR EXISTS (SELECT 1 FROM workspace_model_assignment WHERE purpose = 'CONVERSATION' AND enabled)
    THEN
        RAISE EXCEPTION 'restore clone lockdown did not engage';
    END IF;
END
$$;

COMMIT;
