CREATE FUNCTION public.auth_event_block_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
            BEGIN
                IF TG_OP = 'DELETE' THEN
                    RAISE EXCEPTION 'auth_event is append-only: DELETE is not permitted (use partition retention)';
                END IF;
                -- Permit ONLY the GDPR Art. 17 redaction: the three PII columns set to NULL and NOTHING
                -- else changed. Comparing the whole row MINUS the redactable columns (to_jsonb - keys) is
                -- column-addition-proof: a future column is automatically protected with no edit here,
                -- closing the "enumerate every column" maintenance hazard.
                IF NEW.ip_inet IS NOT NULL
                   OR NEW.user_agent IS NOT NULL
                   OR NEW.details IS NOT NULL
                   OR (to_jsonb(NEW) - 'ip_inet' - 'user_agent' - 'details')
                        IS DISTINCT FROM (to_jsonb(OLD) - 'ip_inet' - 'user_agent' - 'details')
                THEN
                    RAISE EXCEPTION 'auth_event is append-only: only GDPR redaction (NULL ip_inet/user_agent/details) is permitted';
                END IF;
                RETURN NEW;
            END;
            $$;

CREATE TRIGGER trg_auth_event_block_mutation BEFORE DELETE OR UPDATE ON public.auth_event FOR EACH ROW EXECUTE FUNCTION public.auth_event_block_mutation();

CREATE FUNCTION public.config_audit_event_block_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
            BEGIN
                IF TG_OP = 'DELETE' THEN
                    -- Retention is the ONLY sanctioned delete (ConfigAuditRetentionJob, 365 days).
                    -- Keeping the window here rather than blocking DELETE outright is what lets this
                    -- table stay unpartitioned while still honouring storage limitation (GDPR Art.
                    -- 5(1)(e)). Inside the window the row is untouchable.
                    IF OLD.occurred_at >= now() - interval '365 days' THEN
                        RAISE EXCEPTION 'config_audit_event is append-only: DELETE is only permitted past the 365-day retention window';
                    END IF;
                    RETURN OLD;
                END IF;
                -- Permit ONLY erasure, per column: each redactable column may stay or go to NULL. Per-column
            -- (not all-at-once) so an FK's ON DELETE SET NULL, which touches one column, does not trip
            -- this. Comparing to_jsonb(NEW) minus the redactable keys covers future columns with no edit here.

                IF (NEW.actor_account_id IS NULL OR NEW.actor_account_id = OLD.actor_account_id)
                   AND (NEW.acting_account_id IS NULL OR NEW.acting_account_id = OLD.acting_account_id)
                   AND (NEW.old_value IS NULL OR NEW.old_value = OLD.old_value)
                   AND (NEW.new_value IS NULL OR NEW.new_value = OLD.new_value)
                   AND (to_jsonb(NEW) - 'actor_account_id' - 'acting_account_id' - 'old_value' - 'new_value')
                        IS NOT DISTINCT FROM (to_jsonb(OLD) - 'actor_account_id' - 'acting_account_id' - 'old_value' - 'new_value')
                THEN
                    RETURN NEW;
                END IF;
                RAISE EXCEPTION 'config_audit_event is append-only: only erasure (setting actor refs / snapshots to NULL) is permitted';
            END;
            $$;

CREATE TRIGGER trg_config_audit_event_block_mutation BEFORE DELETE OR UPDATE ON public.config_audit_event FOR EACH ROW EXECUTE FUNCTION public.config_audit_event_block_mutation();

CREATE FUNCTION public.config_audit_event_block_truncate() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
            BEGIN
                RAISE EXCEPTION 'config_audit_event is append-only: TRUNCATE is not permitted';
            END;
            $$;

CREATE TRIGGER trg_config_audit_event_block_truncate BEFORE TRUNCATE ON public.config_audit_event FOR EACH STATEMENT EXECUTE FUNCTION public.config_audit_event_block_truncate();
