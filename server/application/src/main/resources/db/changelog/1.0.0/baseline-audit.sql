-- The audit objects that only the prod context has. A dev or test database keeps auth_event and config_audit_event
-- writable, so tests can clean up their rows.
CREATE FUNCTION public.auth_event_block_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'auth_event is append-only'; END IF;
 IF (NEW.account_id IS NULL OR NEW.account_id IS NOT DISTINCT FROM OLD.account_id)
 AND (NEW.acting_account_id IS NULL OR NEW.acting_account_id IS NOT DISTINCT FROM OLD.acting_account_id)
 AND (NEW.viewed_account_id IS NULL OR NEW.viewed_account_id IS NOT DISTINCT FROM OLD.viewed_account_id)
 AND (NEW.viewed_actor_id IS NULL OR NEW.viewed_actor_id IS NOT DISTINCT FROM OLD.viewed_actor_id)
 AND (NEW.identity_id IS NULL OR NEW.identity_id IS NOT DISTINCT FROM OLD.identity_id)
 AND (NEW.provider_id IS NULL OR NEW.provider_id IS NOT DISTINCT FROM OLD.provider_id)
 AND (NEW.workspace_id IS NULL OR NEW.workspace_id IS NOT DISTINCT FROM OLD.workspace_id)
 AND (NEW.ip_inet IS NULL OR NEW.ip_inet IS NOT DISTINCT FROM OLD.ip_inet)
 AND (NEW.user_agent IS NULL OR NEW.user_agent IS NOT DISTINCT FROM OLD.user_agent)
 AND (NEW.details IS NULL OR NEW.details IS NOT DISTINCT FROM OLD.details)
 AND (to_jsonb(NEW)-'account_id'-'acting_account_id'-'viewed_account_id'-'viewed_actor_id'-'identity_id'-'provider_id'-'workspace_id'-'ip_inet'-'user_agent'-'details')
 IS NOT DISTINCT FROM (to_jsonb(OLD)-'account_id'-'acting_account_id'-'viewed_account_id'-'viewed_actor_id'-'identity_id'-'provider_id'-'workspace_id'-'ip_inet'-'user_agent'-'details') THEN
  RETURN NEW; END IF;
 RAISE EXCEPTION 'auth_event is append-only: permits only nulling private attribution and content';
END $$;

CREATE FUNCTION public.config_audit_event_block_truncate() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
            BEGIN
                RAISE EXCEPTION 'config_audit_event is append-only: TRUNCATE is not permitted';
            END;
            $$;

CREATE TRIGGER trg_auth_event_block_mutation BEFORE DELETE OR UPDATE ON public.auth_event FOR EACH ROW EXECUTE FUNCTION public.auth_event_block_mutation();

CREATE TRIGGER trg_config_audit_event_block_mutation BEFORE DELETE OR UPDATE ON public.config_audit_event FOR EACH ROW EXECUTE FUNCTION public.config_audit_event_block_mutation();

CREATE TRIGGER trg_config_audit_event_block_truncate BEFORE TRUNCATE ON public.config_audit_event FOR EACH STATEMENT EXECUTE FUNCTION public.config_audit_event_block_truncate();
