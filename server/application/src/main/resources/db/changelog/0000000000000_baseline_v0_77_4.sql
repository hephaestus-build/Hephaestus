CREATE SCHEMA IF NOT EXISTS partman;

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public;

CREATE EXTENSION IF NOT EXISTS pg_partman WITH SCHEMA partman;

CREATE FUNCTION public.enforce_consent_decision_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
            BEGIN
                IF TG_OP = 'UPDATE'
                   AND OLD.account_id IS NOT NULL AND NEW.account_id IS NULL
                   AND OLD.id = NEW.id
                   AND OLD.purpose = NEW.purpose
                   AND OLD.granted = NEW.granted
                   AND OLD.mechanism = NEW.mechanism
                   AND OLD.notice_version = NEW.notice_version
                   AND OLD.notice_sha256 = NEW.notice_sha256
                   AND OLD.occurred_at = NEW.occurred_at THEN
                    RETURN NEW;
                END IF;
                RAISE EXCEPTION 'consent_decision is append-only';
            END;
            $$;

CREATE FUNCTION public.enforce_consent_notice_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
            BEGIN
                RAISE EXCEPTION 'consent_notice is immutable';
            END;
            $$;

CREATE FUNCTION public.enforce_practice_current_revision_projection() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
        BEGIN
            IF NOT EXISTS (
                SELECT 1
                FROM practice practice
                JOIN practice_revision revision
                  ON revision.id = practice.current_revision_id
                 AND revision.practice_id = practice.id
                LEFT JOIN practice_group practice_group
                  ON practice_group.id = practice.practice_group_id
                WHERE practice.id = NEW.id
                  AND revision.slug = practice.slug
                  AND revision.name = practice.name
                  AND revision.applies_to = practice.applies_to
                  AND revision.bindings = practice.bindings
                  AND revision.criteria = practice.criteria
                  AND revision.precompute_script IS NOT DISTINCT FROM practice.precompute_script
                  AND revision.automated_review_policy = practice.automated_review_policy
                  AND revision.why_it_matters IS NOT DISTINCT FROM practice.why_it_matters
                  AND revision.what_good_looks_like IS NOT DISTINCT FROM practice.what_good_looks_like
                  AND revision.group_slug IS NOT DISTINCT FROM practice_group.slug
            ) THEN
                RAISE EXCEPTION 'practice current revision does not match its current projection'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NULL;
        END;
        $$;

CREATE FUNCTION public.enforce_practice_revision_immutability() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
            BEGIN
                IF NEW.practice_id = OLD.practice_id
                   AND NEW.revision_number = OLD.revision_number
                   AND NEW.created_at = OLD.created_at
                   AND NEW.slug IS NOT DISTINCT FROM OLD.slug
                   AND NEW.name IS NOT DISTINCT FROM OLD.name
                   AND NEW.applies_to IS NOT DISTINCT FROM OLD.applies_to
                   AND NEW.bindings IS NOT DISTINCT FROM OLD.bindings
                   AND NEW.criteria = OLD.criteria
                   AND NEW.precompute_script IS NOT DISTINCT FROM OLD.precompute_script
                   AND NEW.automated_review_policy IS NOT DISTINCT FROM OLD.automated_review_policy
                   AND NEW.why_it_matters IS NOT DISTINCT FROM OLD.why_it_matters
                   AND NEW.what_good_looks_like IS NOT DISTINCT FROM OLD.what_good_looks_like
                   AND NEW.group_slug IS NOT DISTINCT FROM OLD.group_slug
                   AND NEW.group_name IS NOT DISTINCT FROM OLD.group_name
                   AND NEW.group_description IS NOT DISTINCT FROM OLD.group_description
                   AND NEW.group_icon IS NOT DISTINCT FROM OLD.group_icon
                   AND NEW.group_color IS NOT DISTINCT FROM OLD.group_color
                   AND (
                       NEW.review_rule_fingerprint IS NOT DISTINCT FROM OLD.review_rule_fingerprint
                       OR OLD.review_rule_fingerprint IS NULL
                   )
                THEN
                    RETURN NEW;
                END IF;
                RAISE EXCEPTION 'practice revisions are immutable' USING ERRCODE = '55000';
            END;
            $$;

CREATE TABLE public.account (
    id bigint NOT NULL,
    display_name character varying(255) NOT NULL,
    primary_email public.citext,
    primary_email_verified_at timestamp with time zone,
    app_role character varying(16) DEFAULT 'USER'::character varying NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_account_app_role CHECK (((app_role)::text = ANY (ARRAY[('USER'::character varying)::text, ('APP_ADMIN'::character varying)::text]))),
    CONSTRAINT ck_account_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('SUSPENDED'::character varying)::text, ('DELETING'::character varying)::text, ('DELETED'::character varying)::text])))
);

CREATE TABLE public.account_export (
    id bigint NOT NULL,
    account_id bigint NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    expires_at timestamp with time zone,
    failure_reason character varying(128),
    payload bytea,
    CONSTRAINT ck_account_export_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text, ('READY'::character varying)::text, ('FAILED'::character varying)::text, ('EXPIRED'::character varying)::text])))
);

ALTER TABLE public.account_export ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.account_export_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.account_feature (
    account_id bigint NOT NULL,
    flag character varying(64) NOT NULL,
    enabled_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE public.account ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.account_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.activity_event (
    id uuid NOT NULL,
    event_key character varying(255) NOT NULL,
    event_type character varying(64) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    actor_id bigint,
    workspace_id bigint NOT NULL,
    repository_id bigint,
    target_type character varying(32),
    target_id bigint,
    xp double precision NOT NULL,
    ingested_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_activity_event_xp_non_negative CHECK ((xp >= (0)::double precision))
);

CREATE TABLE public.agent_job (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    job_type character varying(50) NOT NULL,
    status character varying(20) NOT NULL,
    metadata jsonb,
    output jsonb,
    config_snapshot jsonb NOT NULL,
    job_token text NOT NULL,
    idempotency_key character varying(255),
    exit_code integer,
    error_message text,
    retry_count integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    started_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    job_token_hash character varying(64),
    delivery_status character varying(20),
    delivery_comment_id character varying(255),
    container_logs text,
    llm_model character varying(100),
    llm_total_calls integer,
    llm_total_input_tokens integer,
    llm_total_output_tokens integer,
    llm_total_reasoning_tokens integer,
    llm_cache_read_tokens integer,
    llm_cache_write_tokens integer,
    llm_model_version character varying(50),
    cancellation_reason character varying(32),
    worker_id character varying(255),
    integration_kind character varying(48),
    artifact_kind character varying(64),
    prompt_digest character varying(64),
    inputs_digest character varying(64),
    available_at timestamp with time zone NOT NULL,
    delivery_attempts smallint DEFAULT 0 NOT NULL,
    execution_started_at timestamp with time zone,
    purpose character varying(32),
    hold_reason character varying(32),
    evidence_snapshot jsonb,
    review_readiness jsonb,
    in_chat_prepared_at timestamp with time zone,
    in_app_prepared_at timestamp with time zone,
    practice_rollout_revision bigint DEFAULT 0 NOT NULL,
    practice_trigger_mode character varying(24) DEFAULT 'MANUAL'::character varying NOT NULL,
    trace_id character varying(32) NOT NULL,
    CONSTRAINT chk_agent_job_delivery_status CHECK (((delivery_status IS NULL) OR ((delivery_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('DELIVERED'::character varying)::text, ('FAILED'::character varying)::text])))),
    CONSTRAINT chk_agent_job_practice_trigger_mode CHECK (((practice_trigger_mode)::text = ANY (ARRAY[('AUTO'::character varying)::text, ('MANUAL'::character varying)::text]))),
    CONSTRAINT ck_agent_job_artifact_kind CHECK (((artifact_kind IS NULL) OR (((artifact_kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text) AND (length((artifact_kind)::text) <= 64)))),
    CONSTRAINT ck_agent_job_status CHECK (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('TIMED_OUT'::character varying)::text, ('CANCELLED'::character varying)::text])))
)
WITH (autovacuum_vacuum_scale_factor='0', autovacuum_vacuum_threshold='500');

CREATE TABLE public.artifact_signal (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    artifact_kind character varying(64) NOT NULL,
    artifact_id bigint NOT NULL,
    signal_name character varying(128) NOT NULL,
    revision character varying(128) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    discovered_via character varying(16) NOT NULL,
    state character varying(16) NOT NULL,
    state_reason character varying(48),
    job_id uuid,
    state_changed_at timestamp with time zone NOT NULL,
    last_attempted_at timestamp with time zone,
    requested_by_user_id bigint,
    CONSTRAINT ck_artifact_signal_artifact_kind CHECK ((((artifact_kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text) AND (length((artifact_kind)::text) <= 64))),
    CONSTRAINT ck_artifact_signal_discovered_via CHECK (((discovered_via)::text = ANY (ARRAY[('EVENT'::character varying)::text, ('SYNC'::character varying)::text, ('MANUAL'::character varying)::text, ('BACKFILL'::character varying)::text, ('SWEEP'::character varying)::text]))),
    CONSTRAINT ck_artifact_signal_state CHECK (((state)::text = ANY (ARRAY[('RECORDED'::character varying)::text, ('DEFERRED'::character varying)::text, ('TRIGGERED'::character varying)::text, ('SUPPRESSED'::character varying)::text, ('PENDING'::character varying)::text, ('LAPSED'::character varying)::text])))
);

CREATE TABLE public.auth_event (
    id bigint NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    account_id bigint,
    acting_account_id bigint,
    event_type character varying(48) NOT NULL,
    result character varying(16) NOT NULL,
    failure_reason character varying(64),
    provider_id bigint,
    workspace_id bigint,
    identity_link_id bigint,
    ip_inet inet,
    user_agent character varying(512),
    details jsonb,
    elevated_via_instance_admin boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_auth_event_event_type CHECK (((event_type)::text = ANY (ARRAY[('LOGIN'::character varying)::text, ('LOGIN_FAILED'::character varying)::text, ('LOGOUT'::character varying)::text, ('TOKEN_REFRESH'::character varying)::text, ('JWT_REVOKED'::character varying)::text, ('IDENTITY_LINKED'::character varying)::text, ('IDENTITY_UNLINKED'::character varying)::text, ('IMPERSONATION_BEGIN'::character varying)::text, ('IMPERSONATION_END'::character varying)::text, ('ACCOUNT_DELETED'::character varying)::text, ('EXPORT_REQUESTED'::character varying)::text, ('APP_ROLE_CHANGED'::character varying)::text, ('RESEARCH_CONSENT_REVOKED'::character varying)::text, ('WORKSPACE_ELEVATION'::character varying)::text, ('LLM_CONNECTION_CREATED'::character varying)::text, ('LLM_CONNECTION_UPDATED'::character varying)::text, ('LLM_CONNECTION_DELETED'::character varying)::text, ('LLM_MODEL_CREATED'::character varying)::text, ('LLM_MODEL_UPDATED'::character varying)::text, ('LLM_MODEL_DELETED'::character varying)::text, ('LLM_MODEL_PRICE_CHANGED'::character varying)::text, ('LLM_MODEL_SHARING_CHANGED'::character varying)::text, ('LLM_SETTINGS_CHANGED'::character varying)::text, ('LOGIN_PROVIDER_CREATED'::character varying)::text, ('LOGIN_PROVIDER_UPDATED'::character varying)::text, ('LOGIN_PROVIDER_DELETED'::character varying)::text, ('SILENT_MODE_CHANGED'::character varying)::text]))),
    CONSTRAINT ck_auth_event_result CHECK (((result)::text = ANY (ARRAY[('SUCCESS'::character varying)::text, ('FAILURE'::character varying)::text])))
)
PARTITION BY RANGE (occurred_at);

CREATE SEQUENCE public.auth_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.auth_event_id_seq OWNED BY public.auth_event.id;

CREATE TABLE public.auth_rate_limit_bucket (
    id character varying(255) NOT NULL,
    state bytea,
    expires_at bigint
);

CREATE TABLE public.chat_message (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    metadata jsonb,
    role character varying(16) NOT NULL,
    parent_message_id uuid,
    thread_id uuid NOT NULL,
    parts jsonb DEFAULT '[]'::jsonb NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    status character varying(16) NOT NULL,
    llm_total_calls integer DEFAULT 0 NOT NULL,
    llm_total_input_tokens bigint DEFAULT 0 NOT NULL,
    llm_total_output_tokens bigint DEFAULT 0 NOT NULL,
    llm_total_reasoning_tokens bigint DEFAULT 0 NOT NULL,
    llm_cache_read_tokens bigint DEFAULT 0 NOT NULL,
    llm_cache_write_tokens bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_chat_message_metadata_shape CHECK (((metadata IS NULL) OR (jsonb_typeof(metadata) = 'object'::text))),
    CONSTRAINT chk_chat_message_parts_shape CHECK ((jsonb_typeof(parts) = 'array'::text)),
    CONSTRAINT chk_chat_message_status CHECK (((status)::text = ANY (ARRAY[('in_flight'::character varying)::text, ('completed'::character varying)::text, ('interrupted'::character varying)::text])))
);

CREATE TABLE public.chat_message_vote (
    message_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    is_upvoted boolean NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);

CREATE TABLE public.chat_thread (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    title text,
    user_id bigint,
    workspace_id bigint NOT NULL,
    session_jsonl bytea,
    surface character varying(16) DEFAULT 'WEB'::character varying NOT NULL,
    CONSTRAINT chk_chat_thread_surface CHECK (((surface)::text = ANY (ARRAY[('WEB'::character varying)::text, ('SLACK_DM'::character varying)::text])))
);

CREATE TABLE public.commit_contributor (
    id bigint NOT NULL,
    commit_id bigint NOT NULL,
    user_id bigint,
    role character varying(32) NOT NULL,
    name character varying(255),
    email character varying(255) NOT NULL,
    ordinal integer NOT NULL
);

ALTER TABLE public.commit_contributor ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.commit_contributor_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.commit_file_change (
    id bigint NOT NULL,
    filename character varying(1024) NOT NULL,
    change_type character varying(32) NOT NULL,
    additions integer NOT NULL,
    deletions integer NOT NULL,
    changes integer NOT NULL,
    previous_filename character varying(1024),
    commit_id bigint NOT NULL
);

ALTER TABLE public.commit_file_change ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.commit_file_change_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.commit_pull_request (
    commit_id bigint NOT NULL,
    pull_request_id bigint NOT NULL
);

CREATE TABLE public.config_audit_event (
    id bigint NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    workspace_id bigint,
    actor_kind character varying(16) NOT NULL,
    actor_account_id bigint,
    acting_account_id bigint,
    entity_type character varying(48) NOT NULL,
    entity_id character varying(64) NOT NULL,
    action character varying(16) NOT NULL,
    changed_keys text[] NOT NULL,
    old_value jsonb,
    new_value jsonb,
    elevated_via_instance_admin boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_config_audit_event_action CHECK (((action)::text = ANY (ARRAY[('CREATED'::character varying)::text, ('UPDATED'::character varying)::text, ('DELETED'::character varying)::text]))),
    CONSTRAINT ck_config_audit_event_actor_kind CHECK (((actor_kind)::text = ANY (ARRAY[('USER'::character varying)::text, ('SYSTEM'::character varying)::text, ('IMPERSONATED'::character varying)::text]))),
    CONSTRAINT ck_config_audit_event_entity_type CHECK (((entity_type)::text = ANY (ARRAY[('PRACTICE_REVIEW_SETTINGS'::character varying)::text, ('AGENT_BINDING'::character varying)::text, ('AGENT_CONFIG'::character varying)::text, ('AI_CONFIG_BINDING'::character varying)::text, ('WORKSPACE_ROLE'::character varying)::text, ('WORKSPACE_FEATURES'::character varying)::text, ('WORKSPACE_STATUS'::character varying)::text, ('WORKSPACE_TOKEN'::character varying)::text, ('WORKSPACE_VISIBILITY'::character varying)::text, ('PRACTICE_ACTIVE'::character varying)::text, ('PRACTICE_USAGE'::character varying)::text, ('PRACTICE_DEFINITION'::character varying)::text, ('PRACTICE_GROUP'::character varying)::text, ('PRACTICE_AREA'::character varying)::text, ('CURATED_PRACTICE'::character varying)::text, ('CURATED_PRACTICE_GROUP'::character varying)::text, ('CURATED_PRACTICE_AREA'::character varying)::text, ('WORKSPACE_INSTANCE_LLM_BUDGET'::character varying)::text, ('WORKSPACE_OWN_PROVIDER_LLM_BUDGET'::character varying)::text, ('WORKSPACE_LLM_BUDGET'::character varying)::text, ('WORKSPACE_BYO_LLM_BUDGET'::character varying)::text, ('REVIEW_BACKFILL_RUN'::character varying)::text, ('REVIEW_SWEEP_SCHEDULE'::character varying)::text, ('WORKSPACE_LLM_CONNECTION'::character varying)::text, ('WORKSPACE_LLM_MODEL'::character varying)::text]))),
    CONSTRAINT ck_config_audit_event_scope CHECK (((((entity_type)::text = ANY (ARRAY[('CURATED_PRACTICE'::character varying)::text, ('CURATED_PRACTICE_AREA'::character varying)::text])) AND (workspace_id IS NULL)) OR (((entity_type)::text <> ALL (ARRAY[('CURATED_PRACTICE'::character varying)::text, ('CURATED_PRACTICE_AREA'::character varying)::text])) AND (workspace_id IS NOT NULL))))
);

CREATE SEQUENCE public.config_audit_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.config_audit_event_id_seq OWNED BY public.config_audit_event.id;

CREATE TABLE public.connection (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    kind character varying(48) NOT NULL,
    instance_key character varying(128),
    display_name character varying(256),
    state character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    state_reason character varying(512),
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    credentials_encrypted bytea,
    credentials_alg character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    credentials_key_version integer,
    credentials_rotation_failed_at timestamp(6) with time zone,
    CONSTRAINT ck_connection_credentials_key_version CHECK (((credentials_key_version IS NULL) OR (credentials_key_version > 0))),
    CONSTRAINT ck_connection_kind CHECK (((kind)::text = ANY (ARRAY[('GITHUB'::character varying)::text, ('GITLAB'::character varying)::text, ('SLACK'::character varying)::text, ('OUTLINE'::character varying)::text]))),
    CONSTRAINT ck_connection_state CHECK (((state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('SUSPENDED'::character varying)::text, ('UNINSTALLED'::character varying)::text])))
);

CREATE TABLE public.connection_activity (
    connection_id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    last_event_at timestamp with time zone,
    last_event_type character varying(128)
);

CREATE TABLE public.connection_audit (
    id bigint NOT NULL,
    connection_id bigint NOT NULL,
    event_type character varying(48) NOT NULL,
    from_state character varying(32),
    to_state character varying(32),
    actor_kind character varying(32) NOT NULL,
    actor_ref character varying(256),
    correlation_id character varying(64),
    detail jsonb,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE public.connection_audit ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.connection_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.connection ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.connection_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.consent_decision (
    id bigint NOT NULL,
    account_id bigint,
    purpose character varying(40) NOT NULL,
    granted boolean NOT NULL,
    mechanism character varying(32) NOT NULL,
    notice_version character varying(32) NOT NULL,
    notice_sha256 character varying(64) NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_consent_decision_mechanism CHECK (((mechanism)::text = ANY (ARRAY[('FIRST_LOGIN_INTERSTITIAL'::character varying)::text, ('ACCOUNT_SETTINGS'::character varying)::text]))),
    CONSTRAINT ck_consent_decision_purpose CHECK (((purpose)::text = ANY (ARRAY[('TERMS_ACCEPTANCE'::character varying)::text, ('PRIVACY_NOTICE_ACKNOWLEDGEMENT'::character varying)::text, ('RESEARCH_PARTICIPATION'::character varying)::text])))
);

ALTER TABLE public.consent_decision ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.consent_decision_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.consent_notice (
    version character varying(32) NOT NULL,
    notice_text text NOT NULL,
    sha256 character varying(64) NOT NULL,
    published_at timestamp with time zone NOT NULL
);

CREATE TABLE public.curated_group_override (
    slug character varying(64) CONSTRAINT curated_area_override_slug_not_null NOT NULL,
    name character varying(128),
    description text,
    "position" integer,
    icon character varying(64),
    color character varying(32),
    based_on_digest character varying(128),
    retired_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone CONSTRAINT curated_area_override_created_at_not_null NOT NULL,
    updated_at timestamp(6) with time zone CONSTRAINT curated_area_override_updated_at_not_null NOT NULL,
    version bigint CONSTRAINT curated_area_override_version_not_null NOT NULL,
    CONSTRAINT ck_curated_group_override_based_on CHECK (((based_on_digest IS NULL) OR ((based_on_digest)::text ~ '^area:v1:[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_curated_group_override_position CHECK ((("position" IS NULL) OR ("position" >= 0))),
    CONSTRAINT ck_curated_group_override_says_something CHECK (((name IS NOT NULL) OR (retired_at IS NOT NULL) OR ("position" IS NOT NULL))),
    CONSTRAINT ck_curated_group_override_shape CHECK (((name IS NOT NULL) OR ((description IS NULL) AND (icon IS NULL) AND (color IS NULL) AND (based_on_digest IS NULL))))
);

CREATE TABLE public.curated_practice_override (
    slug character varying(64) NOT NULL,
    name character varying(128),
    applies_to character varying(64),
    bindings jsonb,
    criteria text,
    precompute_script text,
    why_it_matters text,
    what_good_looks_like text,
    group_slug character varying(64),
    "position" integer,
    based_on_digest character varying(128),
    retired_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    automated_review_policy jsonb,
    CONSTRAINT ck_curated_practice_override_applies_to CHECK (((applies_to IS NULL) OR (((applies_to)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text) AND (length((applies_to)::text) <= 64)))),
    CONSTRAINT ck_curated_practice_override_based_on CHECK (((based_on_digest IS NULL) OR ((based_on_digest)::text ~ '^practice:v[12]:[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_curated_practice_override_bindings CHECK (((bindings IS NULL) OR (jsonb_typeof(bindings) = 'array'::text))),
    CONSTRAINT ck_curated_practice_override_position CHECK ((("position" IS NULL) OR ("position" >= 0))),
    CONSTRAINT ck_curated_practice_override_says_something CHECK (((name IS NOT NULL) OR (retired_at IS NOT NULL) OR ("position" IS NOT NULL))),
    CONSTRAINT ck_curated_practice_override_shape CHECK ((((name IS NULL) AND (applies_to IS NULL) AND (bindings IS NULL) AND (criteria IS NULL) AND (precompute_script IS NULL) AND (why_it_matters IS NULL) AND (what_good_looks_like IS NULL) AND (group_slug IS NULL) AND (based_on_digest IS NULL)) OR ((name IS NOT NULL) AND (applies_to IS NOT NULL) AND (bindings IS NOT NULL) AND (criteria IS NOT NULL))))
);

CREATE TABLE public.delivery_policy_evaluation (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    agent_job_id uuid NOT NULL,
    feedback_id uuid,
    admitted_revision bigint NOT NULL,
    evaluated_revision bigint,
    resolver_version character varying(16) NOT NULL,
    surface character varying(24) NOT NULL,
    stage character varying(24) NOT NULL,
    allowed boolean NOT NULL,
    decisive_reason character varying(48),
    checks jsonb NOT NULL,
    facts jsonb NOT NULL,
    evaluated_at timestamp with time zone NOT NULL
);

CREATE TABLE public.discussion (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    number integer NOT NULL,
    title character varying(1024),
    body text,
    html_url character varying(512),
    state character varying(16),
    state_reason character varying(32),
    upvote_count integer,
    is_locked boolean,
    active_lock_reason character varying(32),
    closed_at timestamp with time zone,
    answer_chosen_at timestamp with time zone,
    comment_count integer,
    last_sync_at timestamp with time zone,
    repository_id bigint,
    author_id bigint,
    category_id character varying(128),
    answer_chosen_by_id bigint,
    answer_comment_id bigint,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

CREATE TABLE public.discussion_category (
    id character varying(128) NOT NULL,
    name character varying(255),
    slug character varying(128),
    emoji character varying(32),
    description text,
    is_answerable boolean NOT NULL,
    repository_id bigint,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);

CREATE TABLE public.discussion_comment (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    body text,
    html_url character varying(512),
    is_answer boolean NOT NULL,
    is_minimized boolean NOT NULL,
    minimized_reason character varying(64),
    author_association character varying(32),
    last_sync_at timestamp with time zone,
    discussion_id bigint,
    author_id bigint,
    parent_comment_id bigint,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

ALTER TABLE public.discussion_comment ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.discussion_comment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.discussion ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.discussion_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.discussion_label (
    discussion_id bigint NOT NULL,
    label_id bigint NOT NULL
);

CREATE TABLE public.feedback (
    id uuid NOT NULL,
    agent_job_id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    artifact_kind character varying(64),
    artifact_id bigint,
    recipient_user_id bigint NOT NULL,
    about_user_id bigint CONSTRAINT feedback_subject_user_id_not_null NOT NULL,
    channel character varying(32) NOT NULL,
    "position" integer NOT NULL,
    delivery_state character varying(32) NOT NULL,
    suppression_reason character varying(32),
    body text,
    source character varying(16) NOT NULL,
    replaces_id uuid,
    thread_key character varying(64),
    created_at timestamp(6) with time zone NOT NULL,
    delivered_at timestamp(6) with time zone,
    proposed_placements jsonb DEFAULT '[]'::jsonb NOT NULL,
    reviewed_revision character varying(64),
    proposed_practice_slugs jsonb DEFAULT '[]'::jsonb NOT NULL,
    CONSTRAINT chk_feedback_artifact_kind CHECK (((artifact_kind IS NULL) OR (((artifact_kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text) AND (length((artifact_kind)::text) <= 64)))),
    CONSTRAINT chk_feedback_channel CHECK (((channel)::text = ANY (ARRAY[('IN_CONTEXT'::character varying)::text, ('IN_CHAT'::character varying)::text, ('IN_APP'::character varying)::text]))),
    CONSTRAINT chk_feedback_proposed_placements CHECK ((jsonb_typeof(proposed_placements) = 'array'::text)),
    CONSTRAINT chk_feedback_proposed_practice_slugs CHECK (((jsonb_typeof(proposed_practice_slugs) = 'array'::text) AND (NOT jsonb_path_exists(proposed_practice_slugs, '$[*]?(@.type() != "string")'::jsonpath)))),
    CONSTRAINT chk_feedback_source CHECK (((source)::text = 'AGENT'::text)),
    CONSTRAINT chk_feedback_state CHECK (((delivery_state)::text = ANY (ARRAY[('AWAITING_APPROVAL'::character varying)::text, ('PREPARED'::character varying)::text, ('PARTIALLY_DELIVERED'::character varying)::text, ('PARTIALLY_FAILED'::character varying)::text, ('DELIVERED'::character varying)::text, ('DISCARDED'::character varying)::text, ('SUPERSEDED'::character varying)::text, ('SUPPRESSED'::character varying)::text, ('FAILED'::character varying)::text]))),
    CONSTRAINT chk_feedback_suppression_reason CHECK (((suppression_reason IS NULL) OR ((suppression_reason)::text = ANY (ARRAY[('VOLUME_CAPPED'::character varying)::text, ('COMPOSER_DEDUPED'::character varying)::text, ('REACTED_DISPUTED'::character varying)::text, ('REACTED_NOT_APPLICABLE'::character varying)::text, ('CONVERSATION_EXPIRED'::character varying)::text, ('ARTIFACT_GONE'::character varying)::text, ('ARTIFACT_CLOSED'::character varying)::text, ('ARTIFACT_MERGED'::character varying)::text, ('ARTIFACT_DRAFT'::character varying)::text, ('RECIPIENT_OPTED_OUT'::character varying)::text, ('EMPTY_AFTER_SANITIZE'::character varying)::text, ('INSTANCE_SILENCED'::character varying)::text, ('PRACTICE_REQUIRES_APPROVAL'::character varying)::text, ('BACKFILL_QUIET'::character varying)::text, ('WORKSPACE_DISABLED'::character varying)::text, ('APPROVAL_STALE'::character varying)::text, ('APPROVAL_NO_LONGER_ELIGIBLE'::character varying)::text, ('WORKSPACE_DELIVERY_PAUSED'::character varying)::text, ('STALE_ROLLOUT_REVISION'::character varying)::text, ('OUTSIDE_CURRENT_COVERAGE'::character varying)::text]))))
);

CREATE TABLE public.feedback_approval (
    id uuid NOT NULL,
    feedback_id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    actor_account_id bigint,
    decision character varying(16) NOT NULL,
    rejection_reason character varying(32),
    rejection_note character varying(500),
    content_digest character varying(64) NOT NULL,
    decided_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_feedback_approval_decision CHECK (((decision)::text = ANY (ARRAY[('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text]))),
    CONSTRAINT ck_feedback_approval_digest CHECK (((content_digest)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_feedback_approval_reason CHECK (((((decision)::text = 'APPROVED'::text) AND (rejection_reason IS NULL)) OR (((decision)::text = 'REJECTED'::text) AND ((rejection_reason IS NULL) OR ((rejection_reason)::text = ANY (ARRAY[('INCORRECT'::character varying)::text, ('MISSING_CONTEXT'::character varying)::text, ('UNHELPFUL'::character varying)::text, ('DUPLICATE'::character varying)::text, ('INAPPROPRIATE_PLACEMENT'::character varying)::text, ('OTHER'::character varying)::text]))))))
);

CREATE TABLE public.feedback_dispatch (
    id uuid NOT NULL,
    destination_key character varying(96) NOT NULL,
    workspace_id bigint NOT NULL,
    agent_job_id uuid NOT NULL,
    feedback_id uuid,
    destination character varying(40) NOT NULL,
    state character varying(16) NOT NULL,
    body text NOT NULL,
    practice_slugs jsonb NOT NULL,
    package_content jsonb NOT NULL,
    delivered_placements jsonb NOT NULL,
    write_started boolean DEFAULT false NOT NULL,
    delivered_external_ref character varying(255),
    lease_owner character varying(64),
    lease_expires_at timestamp with time zone,
    next_attempt_at timestamp with time zone NOT NULL,
    attempt_count integer NOT NULL,
    suppression_reason character varying(48),
    last_error character varying(512),
    projected_at timestamp with time zone,
    projection_owner character varying(64),
    projection_expires_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_feedback_dispatch_delivery CHECK ((((state)::text <> 'SENT'::text) OR (delivered_external_ref IS NOT NULL) OR (jsonb_array_length(delivered_placements) > 0) OR (body = ''::text))),
    CONSTRAINT chk_feedback_dispatch_destination CHECK ((((destination)::text = ANY (ARRAY[('AUTOMATIC_REVIEW_PACKAGE'::character varying)::text, ('APPROVED_REVIEW_PACKAGE'::character varying)::text])) AND (((destination)::text = 'APPROVED_REVIEW_PACKAGE'::text) = (feedback_id IS NOT NULL)))),
    CONSTRAINT chk_feedback_dispatch_lease CHECK ((((state)::text = 'CLAIMED'::text) = ((lease_owner IS NOT NULL) AND (lease_expires_at IS NOT NULL)))),
    CONSTRAINT chk_feedback_dispatch_package CHECK (((jsonb_typeof(package_content) = 'object'::text) AND (jsonb_typeof(delivered_placements) = 'array'::text))),
    CONSTRAINT chk_feedback_dispatch_practice_slugs CHECK (((jsonb_typeof(practice_slugs) = 'array'::text) AND (NOT jsonb_path_exists(practice_slugs, '$[*]?(@.type() != "string")'::jsonpath)))),
    CONSTRAINT chk_feedback_dispatch_projection CHECK (((projection_owner IS NULL) = (projection_expires_at IS NULL))),
    CONSTRAINT chk_feedback_dispatch_state CHECK (((state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('CLAIMED'::character varying)::text, ('SENT'::character varying)::text, ('SUPPRESSED'::character varying)::text, ('UNCERTAIN'::character varying)::text, ('FAILED'::character varying)::text]))),
    CONSTRAINT chk_feedback_dispatch_suppression CHECK ((((state)::text = 'SUPPRESSED'::text) = (suppression_reason IS NOT NULL)))
);

CREATE TABLE public.feedback_observation (
    feedback_id uuid NOT NULL,
    observation_id uuid NOT NULL,
    role character varying(16) NOT NULL,
    ordinal integer NOT NULL,
    CONSTRAINT chk_feedback_observation_role CHECK (((role)::text = ANY (ARRAY[('PRIMARY'::character varying)::text, ('SUPPORTING'::character varying)::text])))
);

CREATE TABLE public.feedback_placement (
    id uuid NOT NULL,
    feedback_id uuid NOT NULL,
    placement_type character varying(32) NOT NULL,
    anchor_kind character varying(16),
    anchor_path text,
    anchor_start_line integer,
    anchor_end_line integer,
    anchor_side character varying(8),
    posted_comment_ref text,
    created_at timestamp(6) with time zone NOT NULL,
    chat_message_id uuid,
    CONSTRAINT chk_feedback_placement_anchor_kind CHECK (((anchor_kind IS NULL) OR ((anchor_kind)::text = ANY (ARRAY[('LINE'::character varying)::text, ('RANGE'::character varying)::text, ('FILE'::character varying)::text, ('IMAGE'::character varying)::text])))),
    CONSTRAINT chk_feedback_placement_anchor_side CHECK (((anchor_side IS NULL) OR ((anchor_side)::text = ANY (ARRAY[('OLD'::character varying)::text, ('NEW'::character varying)::text])))),
    CONSTRAINT chk_feedback_placement_placement CHECK (((placement_type)::text = ANY (ARRAY[('SUMMARY'::character varying)::text, ('INLINE'::character varying)::text, ('CONVERSATION_TURN'::character varying)::text])))
);

CREATE TABLE public.fx_rate (
    id bigint NOT NULL,
    rate_date date NOT NULL,
    usd_per_eur numeric(12,6) NOT NULL,
    fetched_at timestamp with time zone NOT NULL
);

ALTER TABLE public.fx_rate ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.fx_rate_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.git_commit (
    id bigint NOT NULL,
    sha character varying(40) NOT NULL,
    message character varying(1024) NOT NULL,
    message_body text,
    html_url character varying(512),
    authored_at timestamp with time zone NOT NULL,
    committed_at timestamp with time zone NOT NULL,
    additions integer NOT NULL,
    deletions integer NOT NULL,
    changed_files integer NOT NULL,
    author_email character varying(255),
    committer_email character varying(255),
    signature_valid boolean,
    authored_by_committer boolean,
    committed_via_web boolean,
    parent_count integer,
    last_sync_at timestamp with time zone,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    repository_id bigint NOT NULL,
    author_id bigint,
    committer_id bigint,
    signature_state character varying(32),
    signature_was_signed_by_github boolean,
    signature_signer_login character varying(255),
    parent_shas text,
    status_check_rollup_state character varying(32),
    on_behalf_of_login character varying(255)
);

ALTER TABLE public.git_commit ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.git_commit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.identity_provider (
    id bigint CONSTRAINT git_provider_id_not_null NOT NULL,
    type character varying(10) CONSTRAINT git_provider_type_not_null NOT NULL,
    server_url character varying(512) CONSTRAINT git_provider_server_url_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT git_provider_created_at_not_null NOT NULL
);

ALTER TABLE public.identity_provider ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.git_provider_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.identity_link (
    id bigint NOT NULL,
    account_id bigint NOT NULL,
    provider_id bigint CONSTRAINT identity_link_git_provider_id_not_null NOT NULL,
    subject character varying(255) NOT NULL,
    team_id character varying(255),
    external_actor_id bigint,
    username_at_signup character varying(255),
    email_at_signup character varying(320),
    display_name character varying(255),
    avatar_url text,
    profile_url text,
    linked_at timestamp with time zone DEFAULT now() NOT NULL,
    linked_via character varying(16) DEFAULT 'OAUTH_LOGIN'::character varying NOT NULL,
    last_login_at timestamp with time zone,
    disabled_at timestamp with time zone,
    CONSTRAINT ck_identity_link_linked_via CHECK (((linked_via)::text = ANY (ARRAY[('OAUTH_LOGIN'::character varying)::text, ('MANUAL_LINK'::character varying)::text])))
);

ALTER TABLE public.identity_link ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.identity_link_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.instance_llm_settings (
    id smallint NOT NULL,
    allowed_egress_hosts text,
    allow_workspace_connections boolean DEFAULT true NOT NULL,
    updated_at timestamp with time zone,
    updated_by character varying(255),
    CONSTRAINT ck_instance_llm_settings_singleton CHECK ((id = 1))
);

CREATE TABLE public.instance_settings (
    id bigint NOT NULL,
    silent_mode_engaged boolean DEFAULT true NOT NULL,
    silent_mode_reason character varying(500),
    silent_mode_changed_at timestamp with time zone,
    silent_mode_changed_by character varying(255),
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_instance_settings_singleton CHECK ((id = 1))
);

CREATE TABLE public.issue (
    issue_type character varying(31) NOT NULL,
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    closed_at timestamp with time zone,
    comments_count integer NOT NULL,
    html_url character varying(255),
    is_locked boolean NOT NULL,
    number integer NOT NULL,
    state character varying(255),
    title character varying(1024),
    additions integer,
    changed_files integer,
    commits integer,
    deletions integer,
    is_draft boolean,
    is_merged boolean,
    merged_at timestamp with time zone,
    author_id bigint,
    milestone_id bigint,
    repository_id bigint,
    merged_by_id bigint,
    last_sync_at timestamp with time zone,
    state_reason character varying(32),
    body text,
    parent_issue_id bigint,
    sub_issues_total integer,
    sub_issues_completed integer,
    sub_issues_percent_completed integer,
    issue_type_id character varying(128),
    merge_state_status character varying(255),
    mergeable boolean,
    review_decision character varying(255),
    head_ref_name character varying(255),
    head_ref_oid character varying(40),
    base_ref_name character varying(255),
    base_ref_oid character varying(40),
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL,
    merge_commit_sha character varying(40),
    deleted_at timestamp with time zone,
    CONSTRAINT issue_merge_state_status_check CHECK (((merge_state_status)::text = ANY (ARRAY['BEHIND'::text, 'BLOCKED'::text, 'CLEAN'::text, 'DIRTY'::text, 'HAS_HOOKS'::text, 'UNKNOWN'::text, 'UNSTABLE'::text]))),
    CONSTRAINT issue_review_decision_check CHECK (((review_decision)::text = ANY (ARRAY['APPROVED'::text, 'CHANGES_REQUESTED'::text, 'REVIEW_REQUIRED'::text])))
);

CREATE TABLE public.issue_assignee (
    issue_id bigint NOT NULL,
    user_id bigint NOT NULL
);

CREATE TABLE public.issue_blocking (
    blocked_issue_id bigint NOT NULL,
    blocking_issue_id bigint NOT NULL
);

CREATE TABLE public.issue_comment (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    author_association character varying(255),
    html_url character varying(255),
    author_id bigint,
    issue_id bigint,
    body text,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

ALTER TABLE public.issue_comment ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.issue_comment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.issue ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.issue_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.issue_label (
    issue_id bigint NOT NULL,
    label_id bigint NOT NULL
);

CREATE TABLE public.issue_type (
    id character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    description text,
    color character varying(20) NOT NULL,
    is_enabled boolean NOT NULL,
    organization_id bigint NOT NULL,
    last_sync_at timestamp with time zone
);

CREATE TABLE public.issued_jwt (
    jti uuid NOT NULL,
    account_id bigint NOT NULL,
    issued_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    revoked_reason character varying(32),
    user_agent character varying(512),
    ip_inet inet
);

CREATE TABLE public.jwt_signing_key (
    kid character varying(64) NOT NULL,
    algorithm character varying(16) DEFAULT 'ES256'::character varying NOT NULL,
    public_key_pem text NOT NULL,
    private_key_pem bytea NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    encryption_key_id character varying(64) NOT NULL
);

CREATE TABLE public.label (
    id bigint NOT NULL,
    color character varying(255),
    description character varying(255),
    name character varying(255),
    repository_id bigint,
    last_sync_at timestamp with time zone,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

ALTER TABLE public.label ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.label_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.llm_connection (
    id bigint NOT NULL,
    slug character varying(64) NOT NULL,
    display_name character varying(128) NOT NULL,
    base_url character varying(2048) NOT NULL,
    api_protocol character varying(40) NOT NULL,
    auth_mode character varying(16) DEFAULT 'BEARER'::character varying NOT NULL,
    api_key text,
    enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT ck_llm_connection_api_protocol CHECK (((api_protocol)::text = ANY (ARRAY[('openai-completions'::character varying)::text, ('openai-responses'::character varying)::text]))),
    CONSTRAINT ck_llm_connection_auth_mode CHECK (((auth_mode)::text = ANY (ARRAY[('BEARER'::character varying)::text, ('API_KEY'::character varying)::text])))
);

ALTER TABLE public.llm_connection ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.llm_connection_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.llm_model (
    id bigint NOT NULL,
    connection_id bigint NOT NULL,
    slug character varying(64) NOT NULL,
    display_name character varying(128) NOT NULL,
    upstream_model_id character varying(256) NOT NULL,
    context_window integer,
    max_output_tokens integer,
    supports_reasoning boolean DEFAULT false NOT NULL,
    visibility character varying(16) DEFAULT 'GRANTED'::character varying NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT ck_llm_model_visibility CHECK (((visibility)::text = ANY (ARRAY[('PUBLIC'::character varying)::text, ('GRANTED'::character varying)::text])))
);

ALTER TABLE public.llm_model ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.llm_model_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.llm_model_price (
    id bigint NOT NULL,
    model_id bigint NOT NULL,
    pricing_mode character varying(16) NOT NULL,
    per_1m_input_usd numeric(18,8),
    per_1m_output_usd numeric(18,8),
    per_1m_cache_read_usd numeric(18,8),
    per_1m_cache_write_usd numeric(18,8),
    currency character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    note character varying(500),
    effective_from timestamp with time zone NOT NULL,
    effective_to timestamp with time zone,
    created_by character varying(255),
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_llm_model_price_mode CHECK (((pricing_mode)::text = ANY (ARRAY[('PRICED'::character varying)::text, ('NO_CHARGE'::character varying)::text, ('UNPRICED'::character varying)::text]))),
    CONSTRAINT ck_llm_model_price_shape CHECK (((((pricing_mode)::text = 'PRICED'::text) AND (per_1m_input_usd IS NOT NULL) AND (per_1m_input_usd >= (0)::numeric) AND (per_1m_output_usd IS NOT NULL) AND (per_1m_output_usd >= (0)::numeric) AND ((per_1m_cache_read_usd IS NULL) OR (per_1m_cache_read_usd >= (0)::numeric)) AND ((per_1m_cache_write_usd IS NULL) OR (per_1m_cache_write_usd >= (0)::numeric)) AND (GREATEST(per_1m_input_usd, per_1m_output_usd, COALESCE(per_1m_cache_read_usd, (0)::numeric), COALESCE(per_1m_cache_write_usd, (0)::numeric)) > (0)::numeric)) OR (((pricing_mode)::text = 'NO_CHARGE'::text) AND (note IS NOT NULL) AND (btrim((note)::text) <> ''::text) AND (per_1m_input_usd IS NULL) AND (per_1m_output_usd IS NULL) AND (per_1m_cache_read_usd IS NULL) AND (per_1m_cache_write_usd IS NULL)) OR (((pricing_mode)::text = 'UNPRICED'::text) AND (per_1m_input_usd IS NULL) AND (per_1m_output_usd IS NULL) AND (per_1m_cache_read_usd IS NULL) AND (per_1m_cache_write_usd IS NULL)))),
    CONSTRAINT ck_llm_model_price_window CHECK (((effective_to IS NULL) OR (effective_to > effective_from)))
);

ALTER TABLE public.llm_model_price ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.llm_model_price_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.llm_model_workspace_grant (
    model_id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    granted_at timestamp with time zone NOT NULL,
    granted_by character varying(255)
);

CREATE TABLE public.llm_usage_event (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    job_type character varying(40) NOT NULL,
    source_type character varying(20) NOT NULL,
    source_id uuid NOT NULL,
    source_attempt integer DEFAULT 0 NOT NULL,
    model character varying(128),
    input_tokens bigint DEFAULT 0 NOT NULL,
    output_tokens bigint DEFAULT 0 NOT NULL,
    cache_read_tokens bigint DEFAULT 0 NOT NULL,
    cache_write_tokens bigint DEFAULT 0 NOT NULL,
    reasoning_tokens bigint DEFAULT 0 NOT NULL,
    total_calls integer DEFAULT 1 NOT NULL,
    cost_usd numeric(18,6),
    pricing_state character varying(16) DEFAULT 'UNPRICED'::character varying NOT NULL,
    funding_source character varying(16) DEFAULT 'INSTANCE'::character varying NOT NULL,
    applied_price_id bigint,
    applied_workspace_model_id bigint,
    applied_per_1m_input_usd numeric(18,8),
    applied_per_1m_output_usd numeric(18,8),
    applied_per_1m_cache_read_usd numeric(18,8),
    applied_per_1m_cache_write_usd numeric(18,8),
    occurred_at timestamp with time zone NOT NULL,
    usage_provenance character varying(16),
    CONSTRAINT ck_llm_usage_event_funding_source CHECK (((funding_source)::text = ANY (ARRAY[('INSTANCE'::character varying)::text, ('WORKSPACE'::character varying)::text]))),
    CONSTRAINT ck_llm_usage_event_pricing_state CHECK (((pricing_state)::text = ANY (ARRAY[('PRICED'::character varying)::text, ('NO_CHARGE'::character varying)::text, ('UNPRICED'::character varying)::text]))),
    CONSTRAINT ck_llm_usage_event_source_attempt CHECK ((source_attempt >= 0)),
    CONSTRAINT ck_llm_usage_event_source_type CHECK (((source_type)::text = ANY (ARRAY[('AGENT_JOB'::character varying)::text, ('MENTOR_TURN'::character varying)::text]))),
    CONSTRAINT ck_llm_usage_event_unpriced_has_no_cost CHECK ((((pricing_state)::text = 'UNPRICED'::text) = (cost_usd IS NULL))),
    CONSTRAINT ck_llm_usage_event_usage_provenance CHECK (((usage_provenance IS NULL) OR ((usage_provenance)::text = ANY (ARRAY[('RUNNER'::character varying)::text, ('PROXY'::character varying)::text, ('MERGED'::character varying)::text, ('NONE'::character varying)::text]))))
);

CREATE TABLE public.login_provider (
    id bigint NOT NULL,
    registration_id character varying(64) NOT NULL,
    type character varying(10) NOT NULL,
    display_name character varying(255) NOT NULL,
    base_url character varying(512) NOT NULL,
    client_id character varying(512) NOT NULL,
    client_secret text NOT NULL,
    scopes character varying(512) NOT NULL,
    enabled boolean NOT NULL,
    seeded_from_env boolean NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_login_provider_type CHECK (((type)::text = ANY (ARRAY[('GITHUB'::character varying)::text, ('GITLAB'::character varying)::text, ('SLACK'::character varying)::text, ('OUTLINE'::character varying)::text])))
);

ALTER TABLE public.login_provider ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.login_provider_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.mentor_slack_thread (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    chat_thread_id uuid NOT NULL,
    slack_team_id character varying(32) NOT NULL,
    slack_channel_id character varying(32) NOT NULL,
    slack_thread_ts character varying(32) NOT NULL,
    slack_user_id character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.milestone (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    closed_at timestamp with time zone,
    due_on timestamp with time zone,
    html_url character varying(255),
    number integer NOT NULL,
    state character varying(255),
    title character varying(255),
    creator_id bigint,
    repository_id bigint,
    description text,
    closed_issues_count integer NOT NULL,
    open_issues_count integer NOT NULL,
    last_sync_at timestamp with time zone,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

ALTER TABLE public.milestone ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.milestone_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.oauth_state_nonce (
    nonce character varying(32) NOT NULL,
    workspace_id bigint NOT NULL,
    kind character varying(48) NOT NULL,
    issued_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone
);

CREATE TABLE public.observation (
    id uuid CONSTRAINT practice_finding_id_not_null NOT NULL,
    occurrence_key character varying(255) CONSTRAINT practice_finding_idempotency_key_not_null NOT NULL,
    agent_job_id uuid CONSTRAINT practice_finding_agent_job_id_not_null NOT NULL,
    practice_id bigint CONSTRAINT practice_finding_practice_id_not_null NOT NULL,
    artifact_kind character varying(64) CONSTRAINT practice_finding_target_type_not_null NOT NULL,
    artifact_id bigint CONSTRAINT practice_finding_target_id_not_null NOT NULL,
    about_user_id bigint CONSTRAINT practice_finding_contributor_id_not_null NOT NULL,
    summary character varying(255) CONSTRAINT practice_finding_title_not_null NOT NULL,
    presence character varying(16) CONSTRAINT practice_finding_verdict_not_null NOT NULL,
    severity character varying(16),
    evidence jsonb,
    evidence_rationale text,
    observed_at timestamp(6) with time zone CONSTRAINT practice_finding_detected_at_not_null NOT NULL,
    recurrence_key character varying(64),
    practice_revision_id bigint,
    assessment character varying(8),
    origin character varying(16) DEFAULT 'LIVE'::character varying NOT NULL,
    workspace_id bigint NOT NULL,
    CONSTRAINT chk_observation_artifact_kind CHECK ((((artifact_kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text) AND (length((artifact_kind)::text) <= 64))),
    CONSTRAINT chk_observation_assessment CHECK (((assessment IS NULL) OR ((assessment)::text = ANY (ARRAY[('GOOD'::character varying)::text, ('BAD'::character varying)::text])))),
    CONSTRAINT chk_observation_origin CHECK (((origin)::text = ANY (ARRAY[('LIVE'::character varying)::text, ('MANUAL'::character varying)::text, ('BACKFILL'::character varying)::text]))),
    CONSTRAINT chk_observation_presence CHECK (((presence)::text = ANY (ARRAY[('PRESENT'::character varying)::text, ('ABSENT'::character varying)::text, ('NOT_APPLICABLE'::character varying)::text, ('INCONCLUSIVE'::character varying)::text]))),
    CONSTRAINT chk_observation_presence_assessment CHECK (((((presence)::text = ANY (ARRAY[('NOT_APPLICABLE'::character varying)::text, ('INCONCLUSIVE'::character varying)::text])) AND (assessment IS NULL)) OR (((presence)::text = ANY (ARRAY[('PRESENT'::character varying)::text, ('ABSENT'::character varying)::text])) AND (assessment IS NOT NULL)))),
    CONSTRAINT chk_observation_severity CHECK (((severity)::text = ANY (ARRAY[('CRITICAL'::character varying)::text, ('MAJOR'::character varying)::text, ('MINOR'::character varying)::text, ('INFO'::character varying)::text])))
);

CREATE TABLE public.organization (
    id bigint NOT NULL,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    avatar_url character varying(255),
    native_id bigint NOT NULL,
    html_url character varying(255),
    login character varying(255) NOT NULL,
    name character varying(255),
    last_sync_at timestamp with time zone,
    provider_id bigint NOT NULL
);

ALTER TABLE public.organization ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.organization_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.organization_membership (
    organization_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role character varying(32) NOT NULL
);

CREATE TABLE public.outline_collection (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    connection_id bigint NOT NULL,
    collection_id character varying(64) NOT NULL,
    name character varying(1024),
    url_id character varying(512),
    color character varying(32),
    icon character varying(64),
    state character varying(16) NOT NULL,
    sync_status character varying(16) NOT NULL,
    documents_synced_at timestamp with time zone,
    last_sync_error character varying(2048),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    documents_upstream integer,
    exports_skipped_for_budget integer,
    description character varying(2048),
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_outline_collection_state CHECK (((state)::text = ANY (ARRAY[('ENABLED'::character varying)::text, ('PAUSED'::character varying)::text]))),
    CONSTRAINT ck_outline_collection_sync_status CHECK (((sync_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('COMPLETE'::character varying)::text])))
);

ALTER TABLE public.outline_collection ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.outline_collection_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.outline_document (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    connection_id bigint NOT NULL,
    document_id character varying(64) NOT NULL,
    collection_id character varying(64) NOT NULL,
    collection_slug character varying(512),
    parent_document_id character varying(64),
    title character varying(1024),
    slug character varying(512),
    body_markdown text,
    content_hash character varying(64),
    outline_updated_at timestamp with time zone,
    deleted_at timestamp with time zone,
    last_materialized_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    created_by_subject character varying(64),
    created_by_name character varying(255),
    updated_by_subject character varying(64),
    updated_by_name character varying(255),
    outline_created_at timestamp with time zone,
    collaborator_subjects jsonb,
    body_evicted_at timestamp with time zone,
    archived_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_outline_document_tombstone CHECK (((deleted_at IS NULL) OR ((body_markdown IS NULL) AND (content_hash IS NULL) AND (created_by_subject IS NULL) AND (updated_by_subject IS NULL) AND (collaborator_subjects IS NULL))))
);

CREATE TABLE public.outline_document_event (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    connection_id bigint NOT NULL,
    document_id character varying(64) NOT NULL,
    event_name character varying(64) NOT NULL,
    actor_subject character varying(64),
    occurred_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE public.outline_document_event ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.outline_document_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.outline_document ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.outline_document_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.practice (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slug character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    bindings jsonb DEFAULT '[]'::jsonb CONSTRAINT practice_trigger_events_not_null NOT NULL,
    criteria text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    precompute_script text,
    practice_group_id bigint,
    applies_to character varying(64) DEFAULT 'scm.pull_request'::character varying NOT NULL,
    why_it_matters text,
    what_good_looks_like text,
    display_order integer DEFAULT 0 NOT NULL,
    current_revision_id bigint,
    source_curated_slug character varying(64),
    source_curated_fingerprint character varying(96),
    automated_review_policy jsonb NOT NULL,
    autonomy character varying(16),
    CONSTRAINT chk_practice_applies_to CHECK ((((applies_to)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text) AND (length((applies_to)::text) <= 64))),
    CONSTRAINT chk_practice_autonomy CHECK (((autonomy IS NULL) OR ((autonomy)::text = ANY (ARRAY[('OFF'::character varying)::text, ('HUMAN_APPROVAL'::character varying)::text, ('AUTOMATIC'::character varying)::text])))),
    CONSTRAINT ck_practice_curated_fingerprint CHECK (((source_curated_fingerprint IS NULL) OR ((source_curated_fingerprint)::text ~ '^v[1-9][0-9]*:[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_practice_curated_origin CHECK (((source_curated_slug IS NULL) = (source_curated_fingerprint IS NULL)))
);

CREATE TABLE public.practice_group (
    id bigint CONSTRAINT practice_area_id_not_null NOT NULL,
    workspace_id bigint CONSTRAINT practice_area_workspace_id_not_null NOT NULL,
    slug character varying(64) CONSTRAINT practice_area_slug_not_null NOT NULL,
    name character varying(128) CONSTRAINT practice_area_name_not_null NOT NULL,
    description text,
    visible_in_practice_dashboards boolean CONSTRAINT practice_area_is_active_not_null NOT NULL,
    display_order integer CONSTRAINT practice_area_display_order_not_null NOT NULL,
    created_at timestamp(6) with time zone CONSTRAINT practice_area_created_at_not_null NOT NULL,
    updated_at timestamp(6) with time zone,
    icon character varying(64),
    color character varying(32),
    source_curated_slug character varying(64),
    source_curated_fingerprint character varying(64),
    autonomy character varying(16),
    CONSTRAINT chk_practice_group_autonomy CHECK (((autonomy IS NULL) OR ((autonomy)::text = ANY (ARRAY[('OFF'::character varying)::text, ('HUMAN_APPROVAL'::character varying)::text, ('AUTOMATIC'::character varying)::text])))),
    CONSTRAINT ck_practice_group_curated_fingerprint CHECK (((source_curated_fingerprint IS NULL) OR ((source_curated_fingerprint)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_practice_group_curated_origin CHECK (((source_curated_slug IS NULL) = (source_curated_fingerprint IS NULL)))
);

ALTER TABLE public.practice_group ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.practice_area_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.practice_catalog_installation (
    workspace_id bigint NOT NULL,
    installed_at timestamp(6) with time zone NOT NULL,
    provenance_linked_at timestamp(6) with time zone
);

ALTER TABLE public.practice ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.practice_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.practice_review_person_target (
    workspace_id bigint NOT NULL,
    user_id bigint NOT NULL
);

CREATE TABLE public.practice_review_repository_target (
    workspace_id bigint NOT NULL,
    repository_monitor_id bigint CONSTRAINT practice_review_repository_targe_repository_monitor_id_not_null NOT NULL,
    base_branches jsonb DEFAULT '[]'::jsonb NOT NULL,
    CONSTRAINT chk_practice_review_repository_target_branches CHECK (((jsonb_typeof(base_branches) = 'array'::text) AND (NOT jsonb_path_exists(base_branches, '$[*]?((@.type() != "string" || @ like_regex "^\\s*$") || @ like_regex "^.{255}.")'::jsonpath))))
);

CREATE TABLE public.practice_revision (
    id bigint NOT NULL,
    practice_id bigint NOT NULL,
    revision_number integer NOT NULL,
    criteria text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    slug character varying(64),
    name character varying(128),
    applies_to character varying(64),
    bindings jsonb,
    precompute_script text,
    why_it_matters text,
    what_good_looks_like text,
    group_slug character varying(64),
    group_name character varying(128),
    group_description text,
    group_icon character varying(64),
    group_color character varying(32),
    review_rule_fingerprint character varying(96),
    automated_review_policy jsonb,
    CONSTRAINT ck_practice_revision_applies_to CHECK (((applies_to IS NULL) OR (((applies_to)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text) AND (length((applies_to)::text) <= 64)))),
    CONSTRAINT ck_practice_revision_bindings CHECK (((bindings IS NULL) OR (jsonb_typeof(bindings) = 'array'::text))),
    CONSTRAINT ck_practice_revision_definition_shape CHECK ((((slug IS NULL) AND (name IS NULL) AND (applies_to IS NULL) AND (bindings IS NULL) AND (review_rule_fingerprint IS NULL)) OR ((slug IS NOT NULL) AND (name IS NOT NULL) AND (applies_to IS NOT NULL) AND (bindings IS NOT NULL)))),
    CONSTRAINT ck_practice_revision_fingerprint CHECK (((review_rule_fingerprint IS NULL) OR ((review_rule_fingerprint)::text ~ '^v[1-9][0-9]*:[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_practice_revision_number CHECK ((revision_number >= 1))
);

ALTER TABLE public.practice_revision ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.practice_revision_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.product_feedback (
    id uuid NOT NULL,
    account_id bigint NOT NULL,
    workspace_id bigint,
    kind character varying(16) NOT NULL,
    message character varying(5000) NOT NULL,
    page_path character varying(500),
    created_at timestamp with time zone NOT NULL,
    submission_minute timestamp with time zone NOT NULL
);

CREATE TABLE public.product_survey (
    id uuid NOT NULL,
    title character varying(160) NOT NULL,
    description character varying(500) NOT NULL,
    questions_json jsonb NOT NULL,
    workspace_id bigint,
    starts_at timestamp with time zone NOT NULL,
    ends_at timestamp with time zone,
    active boolean NOT NULL,
    created_by_account_id bigint,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE public.product_survey_submission (
    id uuid NOT NULL,
    survey_id uuid NOT NULL,
    account_id bigint NOT NULL,
    workspace_id bigint,
    disposition character varying(16) NOT NULL,
    answers_json jsonb,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE public.project (
    id bigint NOT NULL,
    node_id character varying(64),
    owner_type character varying(32) NOT NULL,
    owner_id bigint NOT NULL,
    number integer NOT NULL,
    title character varying(256),
    short_description text,
    readme text,
    template boolean NOT NULL,
    url character varying(512),
    closed boolean NOT NULL,
    closed_at timestamp with time zone,
    is_public boolean NOT NULL,
    creator_id bigint,
    last_sync_at timestamp with time zone,
    item_sync_cursor character varying(256),
    items_synced_at timestamp with time zone,
    field_sync_cursor character varying(256),
    fields_synced_at timestamp with time zone,
    status_update_sync_cursor character varying(256),
    status_updates_synced_at timestamp with time zone,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

CREATE TABLE public.project_field (
    id character varying(64) NOT NULL,
    project_id bigint NOT NULL,
    name character varying(256) NOT NULL,
    data_type character varying(32) NOT NULL,
    options jsonb,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);

CREATE TABLE public.project_field_value (
    id bigint NOT NULL,
    item_id bigint NOT NULL,
    field_id character varying(64) NOT NULL,
    text_value text,
    number_value double precision,
    date_value date,
    single_select_option_id character varying(64),
    iteration_id character varying(64),
    updated_at timestamp with time zone
);

ALTER TABLE public.project_field_value ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.project_field_value_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.project ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.project_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.project_item (
    id bigint NOT NULL,
    node_id character varying(64),
    project_id bigint NOT NULL,
    content_type character varying(32) NOT NULL,
    issue_id bigint,
    content_database_id bigint,
    draft_title character varying(1024),
    draft_body text,
    archived boolean NOT NULL,
    creator_id bigint,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

ALTER TABLE public.project_item ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.project_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.project_status_update (
    id bigint NOT NULL,
    node_id character varying(64),
    project_id bigint NOT NULL,
    body text,
    body_html text,
    start_date date,
    target_date date,
    status character varying(32),
    creator_id bigint,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

ALTER TABLE public.project_status_update ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.project_status_update_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.pull_request_requested_reviewers (
    pull_request_id bigint NOT NULL,
    user_id bigint NOT NULL
);

CREATE TABLE public.pull_request_review (
    id bigint NOT NULL,
    commit_id character varying(255),
    html_url character varying(255),
    is_dismissed boolean NOT NULL,
    state character varying(255),
    submitted_at timestamp with time zone,
    author_id bigint,
    pull_request_id bigint,
    body text,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    author_can_push_to_repository boolean,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

CREATE TABLE public.pull_request_review_comment (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    author_association character varying(255),
    commit_id character varying(255),
    html_url character varying(255),
    line integer NOT NULL,
    original_commit_id character varying(255),
    original_line integer NOT NULL,
    original_start_line integer,
    path character varying(255),
    start_line integer,
    author_id bigint,
    pull_request_id bigint,
    review_id bigint,
    thread_id bigint NOT NULL,
    in_reply_to_id bigint,
    body text,
    diff_hunk text,
    outdated boolean,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL,
    side character varying(16),
    start_side character varying(16)
);

ALTER TABLE public.pull_request_review_comment ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.pull_request_review_comment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.pull_request_review ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.pull_request_review_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.pull_request_review_thread (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    state character varying(20),
    pull_request_id bigint NOT NULL,
    root_comment_id bigint,
    node_id character varying(128),
    path text,
    line integer,
    start_line integer,
    side character varying(16),
    start_side character varying(16),
    outdated boolean,
    collapsed boolean,
    resolved_by_id bigint,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL,
    commit_sha character varying(64),
    original_commit_sha character varying(64)
);

ALTER TABLE public.pull_request_review_thread ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.pull_request_review_thread_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.reaction (
    id uuid CONSTRAINT finding_feedback_id_not_null NOT NULL,
    reactor_user_id bigint CONSTRAINT finding_feedback_contributor_id_not_null NOT NULL,
    action character varying(16),
    explanation text,
    created_at timestamp(6) with time zone CONSTRAINT finding_feedback_created_at_not_null NOT NULL,
    feedback_id uuid NOT NULL,
    recurrence_key character varying(64),
    usefulness character varying(16),
    CONSTRAINT chk_reaction_action CHECK (((action)::text = ANY (ARRAY[('ADDRESSED'::character varying)::text, ('DISPUTED'::character varying)::text, ('NOT_APPLICABLE'::character varying)::text]))),
    CONSTRAINT chk_reaction_disputed_explanation CHECK ((((action)::text <> 'DISPUTED'::text) OR ((explanation IS NOT NULL) AND (length(TRIM(BOTH FROM explanation)) > 0)))),
    CONSTRAINT chk_reaction_response_shape CHECK (((usefulness IS NOT NULL) OR (action IS NOT NULL) OR (explanation IS NULL))),
    CONSTRAINT chk_reaction_usefulness CHECK (((usefulness)::text = ANY (ARRAY[('HELPFUL'::character varying)::text, ('UNHELPFUL'::character varying)::text])))
);

CREATE TABLE public.repository (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    default_branch character varying(255),
    description text,
    html_url character varying(512),
    is_archived boolean NOT NULL,
    is_disabled boolean NOT NULL,
    is_private boolean NOT NULL,
    name character varying(255),
    name_with_owner character varying(150),
    pushed_at timestamp with time zone,
    visibility character varying(255),
    organization_id bigint,
    last_sync_at timestamp with time zone,
    has_discussions_enabled boolean NOT NULL,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

CREATE TABLE public.repository_collaborator (
    repository_id bigint NOT NULL,
    user_id bigint NOT NULL,
    permission character varying(32) NOT NULL
);

ALTER TABLE public.repository ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.repository_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.repository_to_monitor (
    id bigint NOT NULL,
    labels_synced_at timestamp with time zone,
    milestones_synced_at timestamp with time zone,
    name_with_owner character varying(255),
    repository_synced_at timestamp with time zone,
    workspace_id bigint NOT NULL,
    backfill_last_run_at timestamp(6) with time zone,
    collaborators_synced_at timestamp(6) with time zone,
    issue_sync_cursor character varying(255),
    pull_request_sync_cursor character varying(255),
    issue_backfill_high_water_mark integer,
    issue_backfill_checkpoint integer,
    pull_request_backfill_high_water_mark integer,
    pull_request_backfill_checkpoint integer,
    issues_synced_at timestamp with time zone,
    pull_requests_synced_at timestamp with time zone,
    discussion_sync_cursor character varying(255),
    discussions_synced_at timestamp with time zone,
    native_id bigint
);

ALTER TABLE public.repository_to_monitor ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.repository_to_monitor_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.review_backfill_run (
    id uuid NOT NULL,
    workspace_id bigint NOT NULL,
    artifact_kind character varying(64) NOT NULL,
    from_at timestamp with time zone NOT NULL,
    to_at timestamp with time zone NOT NULL,
    status character varying(24) NOT NULL,
    pause_reason character varying(32),
    estimated_artifacts integer NOT NULL,
    estimated_cost_usd numeric(12,4),
    cursor_artifact_id bigint,
    submitted_count integer DEFAULT 0 NOT NULL,
    passed_count integer DEFAULT 0 NOT NULL,
    requested_by_account_id bigint NOT NULL,
    confirmed_by_account_id bigint,
    created_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    discovered_via character varying(16) DEFAULT 'BACKFILL'::character varying NOT NULL,
    sweep_schedule_id uuid,
    CONSTRAINT ck_review_backfill_run_artifact_kind CHECK ((((artifact_kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text) AND (length((artifact_kind)::text) <= 64))),
    CONSTRAINT ck_review_backfill_run_confirmed CHECK ((((status)::text = 'AWAITING_CONFIRMATION'::text) OR ((status)::text = 'CANCELLED'::text) OR (confirmed_by_account_id IS NOT NULL))),
    CONSTRAINT ck_review_backfill_run_counts CHECK (((estimated_artifacts >= 0) AND (submitted_count >= 0) AND (passed_count >= 0) AND (failed_count >= 0))),
    CONSTRAINT ck_review_backfill_run_discovered_via CHECK (((discovered_via)::text = ANY (ARRAY[('BACKFILL'::character varying)::text, ('SWEEP'::character varying)::text]))),
    CONSTRAINT ck_review_backfill_run_pause_coherent CHECK ((((status)::text = 'PAUSED'::text) = (pause_reason IS NOT NULL))),
    CONSTRAINT ck_review_backfill_run_pause_reason CHECK (((pause_reason IS NULL) OR ((pause_reason)::text = ANY (ARRAY[('BUDGET_EXHAUSTED'::character varying)::text, ('REVIEW_MODEL_UNBOUND'::character varying)::text, ('WORKSPACE_UNAVAILABLE'::character varying)::text])))),
    CONSTRAINT ck_review_backfill_run_status CHECK (((status)::text = ANY (ARRAY[('AWAITING_CONFIRMATION'::character varying)::text, ('RUNNING'::character varying)::text, ('PAUSED'::character varying)::text, ('COMPLETED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT ck_review_backfill_run_sweep_confirmed CHECK ((((discovered_via)::text <> 'SWEEP'::text) OR ((sweep_schedule_id IS NOT NULL) AND (confirmed_by_account_id IS NOT NULL)))),
    CONSTRAINT ck_review_backfill_run_window CHECK ((from_at < to_at))
);

CREATE TABLE public.review_sweep_schedule (
    id uuid NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    workspace_id bigint NOT NULL,
    artifact_kind character varying(64) NOT NULL,
    cadence character varying(16) NOT NULL,
    lookback_days integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    next_run_at timestamp with time zone NOT NULL,
    last_run_at timestamp with time zone,
    created_by_account_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_review_sweep_schedule_cadence CHECK (((cadence)::text = ANY (ARRAY[('DAILY'::character varying)::text, ('WEEKLY'::character varying)::text]))),
    CONSTRAINT ck_review_sweep_schedule_kind CHECK ((((artifact_kind)::text ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$'::text) AND (length((artifact_kind)::text) <= 64))),
    CONSTRAINT ck_review_sweep_schedule_lookback CHECK (((lookback_days >= 1) AND (lookback_days <=
CASE cadence
    WHEN 'DAILY'::text THEN 2
    WHEN 'WEEKLY'::text THEN 7
    ELSE NULL::integer
END)))
);

CREATE TABLE public.shedlock (
    name character varying(64) NOT NULL,
    lock_until timestamp without time zone NOT NULL,
    locked_at timestamp without time zone NOT NULL,
    locked_by character varying(255) NOT NULL
);

CREATE TABLE public.slack_channel_consent_event (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slack_channel_id character varying(32) NOT NULL,
    from_state character varying(16),
    to_state character varying(16) NOT NULL,
    actor_user_id bigint,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_slack_channel_consent_event_from_state CHECK (((from_state IS NULL) OR ((from_state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('REVOKED'::character varying)::text])))),
    CONSTRAINT chk_slack_channel_consent_event_to_state CHECK (((to_state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('REVOKED'::character varying)::text])))
);

ALTER TABLE public.slack_channel_consent_event ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.slack_channel_consent_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.slack_message (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slack_team_id character varying(32) NOT NULL,
    slack_channel_id character varying(32) NOT NULL,
    slack_ts character varying(32) NOT NULL,
    slack_thread_ts character varying(32),
    author_slack_user_id character varying(32),
    text text,
    edited_at timestamp with time zone,
    deleted_at timestamp with time zone,
    ingested_at timestamp with time zone DEFAULT now() NOT NULL,
    author_member_id bigint
);

ALTER TABLE public.slack_message ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.slack_message_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.slack_monitored_channel (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slack_team_id character varying(32) NOT NULL,
    slack_channel_id character varying(32) NOT NULL,
    channel_name character varying(256),
    consent_state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    consent_announced_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_history_synced_ts character varying(32),
    history_synced_at timestamp with time zone,
    CONSTRAINT chk_slack_monitored_channel_consent CHECK (((consent_state)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text, ('REVOKED'::character varying)::text])))
);

ALTER TABLE public.slack_monitored_channel ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.slack_monitored_channel_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.slack_participant_consent (
    workspace_id bigint NOT NULL,
    slack_user_id character varying(32) NOT NULL,
    ingestion_opted_out boolean DEFAULT false NOT NULL,
    research_opted_out boolean DEFAULT false NOT NULL,
    source character varying(32),
    decided_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.slack_thread (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slack_channel_id character varying(32) NOT NULL,
    slack_thread_ts character varying(32) NOT NULL,
    first_ts character varying(32),
    last_ts character varying(32),
    message_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    participant_member_ids bigint[] DEFAULT '{}'::bigint[] NOT NULL,
    last_reviewed_ts character varying(32)
);

ALTER TABLE public.slack_thread ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.slack_thread_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.sync_job (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    connection_id bigint NOT NULL,
    kind character varying(48) NOT NULL,
    type character varying(32) NOT NULL,
    trigger_source character varying(32) NOT NULL,
    status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    cancel_requested boolean DEFAULT false NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    heartbeat_at timestamp with time zone,
    items_processed integer,
    items_total integer,
    progress jsonb,
    error_summary text,
    triggered_by_user_id bigint,
    CONSTRAINT ck_sync_job_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('SUCCEEDED_WITH_WARNINGS'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELLED'::character varying)::text]))),
    CONSTRAINT ck_sync_job_trigger_source CHECK (((trigger_source)::text = ANY (ARRAY[('SCHEDULED'::character varying)::text, ('MANUAL'::character varying)::text, ('LIFECYCLE'::character varying)::text, ('SYSTEM'::character varying)::text]))),
    CONSTRAINT ck_sync_job_type CHECK (((type)::text = ANY (ARRAY[('INITIAL'::character varying)::text, ('RECONCILIATION'::character varying)::text, ('BACKFILL'::character varying)::text])))
);

ALTER TABLE public.sync_job ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.sync_job_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.team (
    id bigint NOT NULL,
    name character varying(255),
    created_at timestamp(6) with time zone,
    description text,
    html_url character varying(512),
    last_sync_at timestamp with time zone,
    organization character varying(255),
    parent_id bigint,
    privacy character varying(32),
    updated_at timestamp(6) with time zone,
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL,
    slug character varying(512)
);

ALTER TABLE public.team ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.team_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.team_membership (
    role character varying(32),
    user_id bigint NOT NULL,
    team_id bigint NOT NULL
);

CREATE TABLE public.team_repository_permission (
    permission character varying(32) NOT NULL,
    repository_id bigint NOT NULL,
    team_id bigint NOT NULL
);

CREATE TABLE public."user" (
    id bigint NOT NULL,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    avatar_url character varying(255),
    email character varying(255),
    html_url character varying(255),
    login character varying(255),
    name character varying(255),
    type character varying(255),
    native_id bigint NOT NULL,
    provider_id bigint NOT NULL
);

CREATE TABLE public.user_achievement (
    id uuid NOT NULL,
    user_id bigint NOT NULL,
    achievement_id character varying(64) NOT NULL,
    unlocked_at timestamp with time zone,
    progress_data jsonb NOT NULL,
    version bigint NOT NULL
);

ALTER TABLE public."user" ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.user_preferences (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    participate_in_research boolean DEFAULT false NOT NULL,
    ai_review_enabled boolean NOT NULL
);

ALTER TABLE public.user_preferences ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.user_preferences_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.worker_registry (
    worker_id character varying(255) NOT NULL,
    last_heartbeat timestamp with time zone NOT NULL,
    registered_at timestamp with time zone NOT NULL
)
WITH (autovacuum_vacuum_scale_factor='0', autovacuum_vacuum_threshold='100');

CREATE TABLE public.worker_token_denylist (
    jti character varying(64) NOT NULL,
    revoked_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL
);

CREATE TABLE public.workspace (
    id bigint NOT NULL,
    users_synced_at timestamp with time zone,
    account_login character varying(120) NOT NULL,
    repository_selection character varying(255),
    organization_id bigint,
    account_type character varying(10) NOT NULL,
    created_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    display_name character varying(120) NOT NULL,
    is_publicly_viewable boolean NOT NULL,
    leaderboard_notification_enabled boolean,
    leaderboard_schedule_day integer,
    leaderboard_schedule_time character varying(10),
    slug character varying(64) NOT NULL,
    status character varying(20) NOT NULL,
    updated_at timestamp(6) with time zone,
    members_synced_at timestamp(6) with time zone,
    teams_synced_at timestamp(6) with time zone,
    sub_issues_synced_at timestamp with time zone,
    issue_types_synced_at timestamp with time zone,
    issue_dependencies_synced_at timestamp with time zone,
    practices_enabled boolean DEFAULT false NOT NULL,
    achievements_enabled boolean DEFAULT false NOT NULL,
    leaderboard_enabled boolean DEFAULT false NOT NULL,
    progression_enabled boolean DEFAULT false NOT NULL,
    leagues_enabled boolean DEFAULT false NOT NULL,
    practice_review_auto_trigger_enabled boolean DEFAULT true NOT NULL,
    practice_review_manual_trigger_enabled boolean DEFAULT true NOT NULL,
    mentor_enabled boolean DEFAULT false NOT NULL,
    leaderboard_league_cycle_at timestamp with time zone,
    practice_skip_drafts boolean,
    practice_deliver_to_merged boolean,
    practice_cooldown_minutes integer,
    monthly_llm_budget_usd numeric(10,2),
    monthly_byo_llm_budget_usd numeric(10,2),
    practice_default_autonomy character varying(16),
    practice_repository_coverage_mode character varying(24) DEFAULT 'ALL_MONITORED'::character varying NOT NULL,
    practice_person_coverage_mode character varying(24) DEFAULT 'ALL_ELIGIBLE'::character varying NOT NULL,
    practice_delivery_status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    practice_rollout_revision bigint DEFAULT 0 NOT NULL,
    practice_config_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_workspace_default_autonomy CHECK (((practice_default_autonomy IS NULL) OR ((practice_default_autonomy)::text = ANY (ARRAY[('OFF'::character varying)::text, ('HUMAN_APPROVAL'::character varying)::text, ('AUTOMATIC'::character varying)::text])))),
    CONSTRAINT chk_workspace_practice_delivery_status CHECK (((practice_delivery_status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('PAUSED'::character varying)::text]))),
    CONSTRAINT chk_workspace_practice_person_coverage CHECK (((practice_person_coverage_mode)::text = ANY (ARRAY[('ALL_ELIGIBLE'::character varying)::text, ('SELECTED'::character varying)::text]))),
    CONSTRAINT chk_workspace_practice_repository_coverage CHECK (((practice_repository_coverage_mode)::text = ANY (ARRAY[('ALL_MONITORED'::character varying)::text, ('SELECTED'::character varying)::text])))
);

CREATE TABLE public.workspace_agent_binding (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    purpose character varying(32) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    timeout_seconds integer DEFAULT 10800 NOT NULL,
    max_concurrent_jobs integer DEFAULT 3 NOT NULL,
    allow_internet boolean DEFAULT false NOT NULL,
    instance_model_id bigint,
    workspace_model_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT ck_workspace_agent_binding_single_model CHECK ((num_nonnulls(instance_model_id, workspace_model_id) = 1))
);

ALTER TABLE public.workspace_agent_binding ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.workspace_agent_binding_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE public.workspace ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.workspace_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.workspace_llm_connection (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    slug character varying(64) NOT NULL,
    display_name character varying(128) NOT NULL,
    base_url character varying(2048) NOT NULL,
    api_protocol character varying(40) NOT NULL,
    auth_mode character varying(16) DEFAULT 'BEARER'::character varying NOT NULL,
    api_key text,
    enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT ck_ws_llm_connection_api_protocol CHECK (((api_protocol)::text = ANY (ARRAY[('openai-completions'::character varying)::text, ('openai-responses'::character varying)::text]))),
    CONSTRAINT ck_ws_llm_connection_auth_mode CHECK (((auth_mode)::text = ANY (ARRAY[('BEARER'::character varying)::text, ('API_KEY'::character varying)::text])))
);

ALTER TABLE public.workspace_llm_connection ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.workspace_llm_connection_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.workspace_llm_model (
    id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    connection_id bigint NOT NULL,
    slug character varying(64) NOT NULL,
    display_name character varying(128) NOT NULL,
    upstream_model_id character varying(256) NOT NULL,
    context_window integer,
    max_output_tokens integer,
    supports_reasoning boolean DEFAULT false NOT NULL,
    pricing_mode character varying(16) DEFAULT 'UNPRICED'::character varying NOT NULL,
    per_1m_input_usd numeric(18,8),
    per_1m_output_usd numeric(18,8),
    per_1m_cache_read_usd numeric(18,8),
    per_1m_cache_write_usd numeric(18,8),
    currency character varying(3) DEFAULT 'USD'::character varying NOT NULL,
    price_note character varying(500),
    enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT ck_ws_llm_model_price_shape CHECK (((((pricing_mode)::text = 'PRICED'::text) AND (per_1m_input_usd IS NOT NULL) AND (per_1m_input_usd >= (0)::numeric) AND (per_1m_output_usd IS NOT NULL) AND (per_1m_output_usd >= (0)::numeric) AND ((per_1m_cache_read_usd IS NULL) OR (per_1m_cache_read_usd >= (0)::numeric)) AND ((per_1m_cache_write_usd IS NULL) OR (per_1m_cache_write_usd >= (0)::numeric)) AND (GREATEST(per_1m_input_usd, per_1m_output_usd, COALESCE(per_1m_cache_read_usd, (0)::numeric), COALESCE(per_1m_cache_write_usd, (0)::numeric)) > (0)::numeric)) OR (((pricing_mode)::text = 'NO_CHARGE'::text) AND (price_note IS NOT NULL) AND (btrim((price_note)::text) <> ''::text) AND (per_1m_input_usd IS NULL) AND (per_1m_output_usd IS NULL) AND (per_1m_cache_read_usd IS NULL) AND (per_1m_cache_write_usd IS NULL)) OR (((pricing_mode)::text = 'UNPRICED'::text) AND (per_1m_input_usd IS NULL) AND (per_1m_output_usd IS NULL) AND (per_1m_cache_read_usd IS NULL) AND (per_1m_cache_write_usd IS NULL)))),
    CONSTRAINT ck_ws_llm_model_pricing_mode CHECK (((pricing_mode)::text = ANY (ARRAY[('PRICED'::character varying)::text, ('NO_CHARGE'::character varying)::text, ('UNPRICED'::character varying)::text])))
);

ALTER TABLE public.workspace_llm_model ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.workspace_llm_model_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.workspace_membership (
    created_at timestamp(6) with time zone NOT NULL,
    league_points integer NOT NULL,
    role character varying(16) NOT NULL,
    user_id bigint NOT NULL,
    workspace_id bigint NOT NULL,
    hidden boolean NOT NULL
);

CREATE TABLE public.workspace_slug_history (
    id bigint NOT NULL,
    changed_at timestamp(6) with time zone NOT NULL,
    new_slug character varying(64) NOT NULL,
    old_slug character varying(64) NOT NULL,
    redirect_expires_at timestamp(6) with time zone,
    workspace_id bigint NOT NULL
);

ALTER TABLE public.workspace_slug_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.workspace_slug_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.workspace_team_label_filter (
    workspace_id bigint NOT NULL,
    team_id bigint NOT NULL,
    label_id bigint NOT NULL
);

CREATE TABLE public.workspace_team_repository_settings (
    workspace_id bigint NOT NULL,
    team_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    hidden_from_contributions boolean CONSTRAINT workspace_team_repository_se_hidden_from_contributions_not_null NOT NULL
);

CREATE TABLE public.workspace_team_settings (
    workspace_id bigint NOT NULL,
    team_id bigint NOT NULL,
    hidden boolean NOT NULL
);

ALTER TABLE ONLY public.auth_event ALTER COLUMN id SET DEFAULT nextval('public.auth_event_id_seq'::regclass);

ALTER TABLE ONLY public.config_audit_event ALTER COLUMN id SET DEFAULT nextval('public.config_audit_event_id_seq'::regclass);

ALTER TABLE ONLY public.account_export
    ADD CONSTRAINT account_export_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.account
    ADD CONSTRAINT account_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.activity_event
    ADD CONSTRAINT activity_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.agent_job
    ADD CONSTRAINT agent_job_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.auth_rate_limit_bucket
    ADD CONSTRAINT auth_rate_limit_bucket_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.chat_message
    ADD CONSTRAINT "chat_messagePK" PRIMARY KEY (id);

ALTER TABLE ONLY public.chat_message_vote
    ADD CONSTRAINT "chat_message_votePK" PRIMARY KEY (message_id);

ALTER TABLE ONLY public.chat_thread
    ADD CONSTRAINT "chat_threadPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.config_audit_event
    ADD CONSTRAINT config_audit_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.curated_group_override
    ADD CONSTRAINT curated_area_override_pkey PRIMARY KEY (slug);

ALTER TABLE ONLY public.curated_practice_override
    ADD CONSTRAINT curated_practice_override_pkey PRIMARY KEY (slug);

ALTER TABLE ONLY public.discussion_category
    ADD CONSTRAINT discussion_category_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.discussion_comment
    ADD CONSTRAINT discussion_comment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.discussion_label
    ADD CONSTRAINT discussion_label_pkey PRIMARY KEY (discussion_id, label_id);

ALTER TABLE ONLY public.discussion
    ADD CONSTRAINT discussion_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_model_price
    ADD CONSTRAINT ex_llm_model_price_no_overlap EXCLUDE USING gist (model_id WITH =, tstzrange(effective_from, effective_to) WITH &&);

ALTER TABLE ONLY public.feedback_observation
    ADD CONSTRAINT feedback_observation_pkey PRIMARY KEY (feedback_id, observation_id);

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback_placement
    ADD CONSTRAINT feedback_placement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.identity_link
    ADD CONSTRAINT identity_link_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.instance_settings
    ADD CONSTRAINT instance_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_assignee
    ADD CONSTRAINT issue_assignee_pkey PRIMARY KEY (issue_id, user_id);

ALTER TABLE ONLY public.issue_comment
    ADD CONSTRAINT issue_comment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_label
    ADD CONSTRAINT issue_label_pkey PRIMARY KEY (issue_id, label_id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT issue_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_type
    ADD CONSTRAINT issue_type_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.issued_jwt
    ADD CONSTRAINT issued_jwt_pkey PRIMARY KEY (jti);

ALTER TABLE ONLY public.jwt_signing_key
    ADD CONSTRAINT jwt_signing_key_pkey PRIMARY KEY (kid);

ALTER TABLE ONLY public.label
    ADD CONSTRAINT label_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.login_provider
    ADD CONSTRAINT login_provider_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.mentor_slack_thread
    ADD CONSTRAINT mentor_slack_thread_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT milestone_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.observation
    ADD CONSTRAINT observation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT "organizationPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.organization_membership
    ADD CONSTRAINT "organization_membershipPK" PRIMARY KEY (organization_id, user_id);

ALTER TABLE ONLY public.outline_collection
    ADD CONSTRAINT outline_collection_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.outline_document_event
    ADD CONSTRAINT outline_document_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.outline_document
    ADD CONSTRAINT outline_document_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.account_feature
    ADD CONSTRAINT pk_account_feature PRIMARY KEY (account_id, flag);

ALTER TABLE ONLY public.artifact_signal
    ADD CONSTRAINT pk_artifact_signal PRIMARY KEY (id);

ALTER TABLE ONLY public.auth_event
    ADD CONSTRAINT pk_auth_event PRIMARY KEY (id, occurred_at);

ALTER TABLE ONLY public.commit_contributor
    ADD CONSTRAINT pk_commit_contributor PRIMARY KEY (id);

ALTER TABLE ONLY public.commit_file_change
    ADD CONSTRAINT pk_commit_file_change PRIMARY KEY (id);

ALTER TABLE ONLY public.commit_pull_request
    ADD CONSTRAINT pk_commit_pull_request PRIMARY KEY (commit_id, pull_request_id);

ALTER TABLE ONLY public.connection
    ADD CONSTRAINT pk_connection PRIMARY KEY (id);

ALTER TABLE ONLY public.connection_activity
    ADD CONSTRAINT pk_connection_activity PRIMARY KEY (connection_id);

ALTER TABLE ONLY public.connection_audit
    ADD CONSTRAINT pk_connection_audit PRIMARY KEY (id);

ALTER TABLE ONLY public.consent_decision
    ADD CONSTRAINT pk_consent_decision PRIMARY KEY (id);

ALTER TABLE ONLY public.consent_notice
    ADD CONSTRAINT pk_consent_notice PRIMARY KEY (version);

ALTER TABLE ONLY public.delivery_policy_evaluation
    ADD CONSTRAINT pk_delivery_policy_evaluation PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback_approval
    ADD CONSTRAINT pk_feedback_approval PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback_dispatch
    ADD CONSTRAINT pk_feedback_dispatch PRIMARY KEY (id);

ALTER TABLE ONLY public.fx_rate
    ADD CONSTRAINT pk_fx_rate PRIMARY KEY (id);

ALTER TABLE ONLY public.git_commit
    ADD CONSTRAINT pk_git_commit PRIMARY KEY (id);

ALTER TABLE ONLY public.identity_provider
    ADD CONSTRAINT pk_identity_provider PRIMARY KEY (id);

ALTER TABLE ONLY public.instance_llm_settings
    ADD CONSTRAINT pk_instance_llm_settings PRIMARY KEY (id);

ALTER TABLE ONLY public.issue_blocking
    ADD CONSTRAINT pk_issue_blocking PRIMARY KEY (blocked_issue_id, blocking_issue_id);

ALTER TABLE ONLY public.llm_connection
    ADD CONSTRAINT pk_llm_connection PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_model
    ADD CONSTRAINT pk_llm_model PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_model_price
    ADD CONSTRAINT pk_llm_model_price PRIMARY KEY (id);

ALTER TABLE ONLY public.llm_model_workspace_grant
    ADD CONSTRAINT pk_llm_model_workspace_grant PRIMARY KEY (model_id, workspace_id);

ALTER TABLE ONLY public.llm_usage_event
    ADD CONSTRAINT pk_llm_usage_event PRIMARY KEY (id);

ALTER TABLE ONLY public.oauth_state_nonce
    ADD CONSTRAINT pk_oauth_state_nonce PRIMARY KEY (nonce);

ALTER TABLE ONLY public.practice_review_person_target
    ADD CONSTRAINT pk_practice_review_person_target PRIMARY KEY (user_id, workspace_id);

ALTER TABLE ONLY public.practice_review_repository_target
    ADD CONSTRAINT pk_practice_review_repository_target PRIMARY KEY (repository_monitor_id, workspace_id);

ALTER TABLE ONLY public.repository_collaborator
    ADD CONSTRAINT pk_repository_collaborator PRIMARY KEY (repository_id, user_id);

ALTER TABLE ONLY public.shedlock
    ADD CONSTRAINT pk_shedlock PRIMARY KEY (name);

ALTER TABLE ONLY public.slack_participant_consent
    ADD CONSTRAINT pk_slack_participant_consent PRIMARY KEY (slack_user_id, workspace_id);

ALTER TABLE ONLY public.sync_job
    ADD CONSTRAINT pk_sync_job PRIMARY KEY (id);

ALTER TABLE ONLY public.worker_registry
    ADD CONSTRAINT pk_worker_registry PRIMARY KEY (worker_id);

ALTER TABLE ONLY public.worker_token_denylist
    ADD CONSTRAINT pk_worker_token_denylist PRIMARY KEY (jti);

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT pk_workspace_agent_binding PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_llm_connection
    ADD CONSTRAINT pk_workspace_llm_connection PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT pk_workspace_llm_model PRIMARY KEY (id);

ALTER TABLE ONLY public.practice_catalog_installation
    ADD CONSTRAINT practice_catalog_installation_pkey PRIMARY KEY (workspace_id);

ALTER TABLE ONLY public.practice_group
    ADD CONSTRAINT "practice_groupPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT practice_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.practice_revision
    ADD CONSTRAINT "practice_revisionPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.product_feedback
    ADD CONSTRAINT product_feedback_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.product_survey
    ADD CONSTRAINT product_survey_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.product_survey_submission
    ADD CONSTRAINT product_survey_submission_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.project_field
    ADD CONSTRAINT project_field_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.project_field_value
    ADD CONSTRAINT project_field_value_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.project_item
    ADD CONSTRAINT project_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.project
    ADD CONSTRAINT project_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.project_status_update
    ADD CONSTRAINT project_status_update_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.pull_request_requested_reviewers
    ADD CONSTRAINT pull_request_requested_reviewers_pkey PRIMARY KEY (pull_request_id, user_id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT pull_request_review_comment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.pull_request_review
    ADD CONSTRAINT pull_request_review_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT pull_request_review_thread_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.reaction
    ADD CONSTRAINT reaction_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.repository
    ADD CONSTRAINT repository_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.repository_to_monitor
    ADD CONSTRAINT "repository_to_monitorPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.review_backfill_run
    ADD CONSTRAINT review_backfill_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.review_sweep_schedule
    ADD CONSTRAINT review_sweep_schedule_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.slack_channel_consent_event
    ADD CONSTRAINT slack_channel_consent_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.slack_message
    ADD CONSTRAINT slack_message_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.slack_monitored_channel
    ADD CONSTRAINT slack_monitored_channel_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.slack_thread
    ADD CONSTRAINT slack_thread_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.team
    ADD CONSTRAINT "teamPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.team_membership
    ADD CONSTRAINT "team_membershipPK" PRIMARY KEY (team_id, user_id);

ALTER TABLE ONLY public.team_repository_permission
    ADD CONSTRAINT "team_repository_permissionPK" PRIMARY KEY (repository_id, team_id);

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT uc_workspaceorganization_id_col UNIQUE (organization_id);

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT uc_workspaceslug_col UNIQUE (slug);

ALTER TABLE ONLY public.activity_event
    ADD CONSTRAINT uk_activity_event_workspace_key UNIQUE (workspace_id, event_key);

ALTER TABLE ONLY public.agent_job
    ADD CONSTRAINT uk_agent_job_token UNIQUE (job_token);

ALTER TABLE ONLY public.agent_job
    ADD CONSTRAINT uk_agent_job_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.consent_notice
    ADD CONSTRAINT uk_consent_notice_sha256 UNIQUE (sha256);

ALTER TABLE ONLY public.consent_notice
    ADD CONSTRAINT uk_consent_notice_version_sha256 UNIQUE (version, sha256);

ALTER TABLE ONLY public.discussion
    ADD CONSTRAINT uk_discussion_answer_comment_id UNIQUE (answer_comment_id);

ALTER TABLE ONLY public.feedback_approval
    ADD CONSTRAINT uk_feedback_approval_feedback UNIQUE (feedback_id);

ALTER TABLE ONLY public.feedback_dispatch
    ADD CONSTRAINT uk_feedback_dispatch_key UNIQUE (destination_key);

ALTER TABLE ONLY public.feedback_placement
    ADD CONSTRAINT uk_feedback_placement_feedback_ref UNIQUE (feedback_id, posted_comment_ref);

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT uk_feedback_unit UNIQUE (agent_job_id, "position");

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT uk_feedback_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT uk_issue_repository_type_number UNIQUE (repository_id, issue_type, number);

ALTER TABLE ONLY public.mentor_slack_thread
    ADD CONSTRAINT uk_mentor_slack_thread UNIQUE (workspace_id, slack_channel_id, slack_thread_ts);

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT uk_milestone_number_repository UNIQUE (number, repository_id);

ALTER TABLE ONLY public.observation
    ADD CONSTRAINT uk_observation_occurrence UNIQUE (occurrence_key);

ALTER TABLE ONLY public.outline_collection
    ADD CONSTRAINT uk_outline_collection UNIQUE (workspace_id, connection_id, collection_id);

ALTER TABLE ONLY public.outline_document
    ADD CONSTRAINT uk_outline_document UNIQUE (workspace_id, connection_id, document_id);

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT uk_practice_current_revision UNIQUE (current_revision_id);

ALTER TABLE ONLY public.practice_group
    ADD CONSTRAINT uk_practice_group_workspace_slug UNIQUE (workspace_id, slug);

ALTER TABLE ONLY public.practice_revision
    ADD CONSTRAINT uk_practice_revision_practice_number UNIQUE (practice_id, revision_number);

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT uk_practice_workspace_id UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT uk_practice_workspace_slug UNIQUE (workspace_id, slug);

ALTER TABLE ONLY public.product_feedback
    ADD CONSTRAINT uk_product_feedback_rate_limit UNIQUE (account_id, submission_minute);

ALTER TABLE ONLY public.project_field
    ADD CONSTRAINT uk_project_field_project_name UNIQUE (project_id, name);

ALTER TABLE ONLY public.project_field_value
    ADD CONSTRAINT uk_project_field_value_item_field UNIQUE (item_id, field_id);

ALTER TABLE ONLY public.project_item
    ADD CONSTRAINT uk_project_item_project_nodeid UNIQUE (project_id, node_id);

ALTER TABLE ONLY public.project
    ADD CONSTRAINT uk_project_node_id UNIQUE (node_id);

ALTER TABLE ONLY public.project
    ADD CONSTRAINT uk_project_owner_number UNIQUE (owner_type, owner_id, number);

ALTER TABLE ONLY public.project_status_update
    ADD CONSTRAINT uk_project_status_update_node_id UNIQUE (node_id);

ALTER TABLE ONLY public.repository_to_monitor
    ADD CONSTRAINT uk_repository_to_monitor_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.slack_message
    ADD CONSTRAINT uk_slack_message UNIQUE (workspace_id, slack_channel_id, slack_ts);

ALTER TABLE ONLY public.slack_monitored_channel
    ADD CONSTRAINT uk_slack_monitored_channel UNIQUE (workspace_id, slack_channel_id);

ALTER TABLE ONLY public.slack_thread
    ADD CONSTRAINT uk_slack_thread UNIQUE (workspace_id, slack_channel_id, slack_thread_ts);

ALTER TABLE ONLY public.product_survey_submission
    ADD CONSTRAINT uk_survey_submission_account UNIQUE (survey_id, account_id);

ALTER TABLE ONLY public.team
    ADD CONSTRAINT uk_team_provider_organization_slug UNIQUE (provider_id, organization, slug);

ALTER TABLE ONLY public.user_achievement
    ADD CONSTRAINT uk_user_achievement_user_achievement UNIQUE (user_id, achievement_id);

ALTER TABLE ONLY public.user_preferences
    ADD CONSTRAINT uk_user_preferences_user_id UNIQUE (user_id);

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT uk_workspace_agent_binding_purpose UNIQUE (workspace_id, purpose);

ALTER TABLE ONLY public.artifact_signal
    ADD CONSTRAINT uq_artifact_signal UNIQUE (workspace_id, artifact_kind, artifact_id, signal_name, revision);

ALTER TABLE ONLY public.commit_contributor
    ADD CONSTRAINT uq_commit_contributor_commit_email_role UNIQUE (commit_id, email, role);

ALTER TABLE ONLY public.connection
    ADD CONSTRAINT uq_connection UNIQUE (workspace_id, kind, instance_key);

ALTER TABLE ONLY public.discussion_category
    ADD CONSTRAINT uq_discussion_category_repo_slug UNIQUE (repository_id, slug);

ALTER TABLE ONLY public.discussion_comment
    ADD CONSTRAINT uq_discussion_comment_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.discussion
    ADD CONSTRAINT uq_discussion_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.discussion
    ADD CONSTRAINT uq_discussion_repo_number UNIQUE (repository_id, number);

ALTER TABLE ONLY public.git_commit
    ADD CONSTRAINT uq_git_commit_sha_repository UNIQUE (sha, repository_id);

ALTER TABLE ONLY public.identity_provider
    ADD CONSTRAINT uq_identity_provider_type_server_url UNIQUE (type, server_url);

ALTER TABLE ONLY public.issue_comment
    ADD CONSTRAINT uq_issue_comment_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT uq_issue_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.label
    ADD CONSTRAINT uq_label_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.label
    ADD CONSTRAINT uq_label_repository_name UNIQUE (repository_id, name);

ALTER TABLE ONLY public.login_provider
    ADD CONSTRAINT uq_login_provider_registration_id UNIQUE (registration_id);

ALTER TABLE ONLY public.login_provider
    ADD CONSTRAINT uq_login_provider_type_base_url UNIQUE (type, base_url);

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT uq_milestone_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT uq_organization_provider_login UNIQUE (provider_id, login);

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT uq_organization_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT uq_pr_review_comment_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.pull_request_review
    ADD CONSTRAINT uq_pr_review_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT uq_pr_review_thread_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT uq_pr_review_thread_root_comment UNIQUE (root_comment_id);

ALTER TABLE ONLY public.project_item
    ADD CONSTRAINT uq_project_item_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.project
    ADD CONSTRAINT uq_project_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.project_status_update
    ADD CONSTRAINT uq_project_status_update_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.repository
    ADD CONSTRAINT uq_repository_provider_name_with_owner UNIQUE (provider_id, name_with_owner);

ALTER TABLE ONLY public.repository
    ADD CONSTRAINT uq_repository_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.review_sweep_schedule
    ADD CONSTRAINT uq_review_sweep_schedule_workspace_kind UNIQUE (workspace_id, artifact_kind);

ALTER TABLE ONLY public.team
    ADD CONSTRAINT uq_team_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT uq_user_provider_native_id UNIQUE (provider_id, native_id);

ALTER TABLE ONLY public.user_achievement
    ADD CONSTRAINT user_achievement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.user_preferences
    ADD CONSTRAINT user_preferences_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.connection
    ADD CONSTRAINT ux_connection_id_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.fx_rate
    ADD CONSTRAINT ux_fx_rate_date UNIQUE (rate_date);

ALTER TABLE ONLY public.llm_connection
    ADD CONSTRAINT ux_llm_connection_slug UNIQUE (slug);

ALTER TABLE ONLY public.llm_model
    ADD CONSTRAINT ux_llm_model_connection_slug UNIQUE (connection_id, slug);

ALTER TABLE ONLY public.llm_model
    ADD CONSTRAINT ux_llm_model_connection_upstream UNIQUE (connection_id, upstream_model_id);

ALTER TABLE ONLY public.llm_usage_event
    ADD CONSTRAINT ux_llm_usage_event_source_attempt UNIQUE (source_type, source_id, source_attempt);

ALTER TABLE ONLY public.workspace_llm_connection
    ADD CONSTRAINT ux_ws_llm_connection_id_ws UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.workspace_llm_connection
    ADD CONSTRAINT ux_ws_llm_connection_ws_slug UNIQUE (workspace_id, slug);

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT ux_ws_llm_model_connection_upstream UNIQUE (connection_id, upstream_model_id);

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT ux_ws_llm_model_id_ws UNIQUE (id, workspace_id);

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT ux_ws_llm_model_ws_slug UNIQUE (workspace_id, slug);

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT "workspacePK" PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_membership
    ADD CONSTRAINT "workspace_membershipPK" PRIMARY KEY (user_id, workspace_id);

ALTER TABLE ONLY public.workspace_slug_history
    ADD CONSTRAINT "workspace_slug_historyPK" PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_team_label_filter
    ADD CONSTRAINT "workspace_team_label_filterPK" PRIMARY KEY (label_id, team_id, workspace_id);

ALTER TABLE ONLY public.workspace_team_repository_settings
    ADD CONSTRAINT "workspace_team_repository_settingsPK" PRIMARY KEY (repository_id, team_id, workspace_id);

ALTER TABLE ONLY public.workspace_team_settings
    ADD CONSTRAINT "workspace_team_settingsPK" PRIMARY KEY (team_id, workspace_id);

CREATE UNIQUE INDEX "IX_workspace_team_label_filterPK" ON public.workspace_team_label_filter USING btree (label_id, team_id, workspace_id);

CREATE UNIQUE INDEX "IX_workspace_team_repository_settingsPK" ON public.workspace_team_repository_settings USING btree (repository_id, team_id, workspace_id);

CREATE UNIQUE INDEX "IX_workspace_team_settingsPK" ON public.workspace_team_settings USING btree (team_id, workspace_id);

CREATE INDEX idx_activity_event_actor_occurred ON public.activity_event USING btree (actor_id, occurred_at DESC);

CREATE INDEX idx_activity_event_actor_time ON public.activity_event USING btree (actor_id, occurred_at);

CREATE INDEX idx_activity_event_leaderboard ON public.activity_event USING btree (workspace_id, actor_id, occurred_at);

CREATE INDEX idx_activity_event_leaderboard_covering ON public.activity_event USING btree (workspace_id, occurred_at DESC, actor_id, xp);

CREATE INDEX idx_activity_event_target ON public.activity_event USING btree (target_id, target_type);

CREATE INDEX idx_activity_event_type_time ON public.activity_event USING btree (event_type, occurred_at);

CREATE INDEX idx_activity_event_workspace_actor_occurred ON public.activity_event USING btree (workspace_id, actor_id, occurred_at DESC);

CREATE INDEX idx_activity_event_workspace_occurred ON public.activity_event USING btree (workspace_id, occurred_at DESC);

CREATE INDEX idx_activity_event_workspace_time ON public.activity_event USING btree (workspace_id, occurred_at);

CREATE INDEX idx_activity_event_xp_lookup ON public.activity_event USING btree (workspace_id, target_type, target_id);

CREATE INDEX idx_agent_job_status_started ON public.agent_job USING btree (status, started_at) WHERE ((status)::text = 'RUNNING'::text);

CREATE INDEX idx_agent_job_subject ON public.agent_job USING btree (integration_kind, artifact_kind, created_at DESC);

CREATE INDEX idx_agent_job_unprepared_lanes ON public.agent_job USING btree (completed_at) WHERE (((status)::text = 'COMPLETED'::text) AND ((in_chat_prepared_at IS NULL) OR (in_app_prepared_at IS NULL)));

CREATE INDEX idx_agent_job_workspace_created ON public.agent_job USING btree (workspace_id, created_at DESC, id DESC);

CREATE INDEX idx_agent_job_workspace_purpose_created ON public.agent_job USING btree (workspace_id, purpose, created_at DESC, id DESC);

CREATE INDEX idx_agent_job_workspace_status ON public.agent_job USING btree (workspace_id, status);

CREATE INDEX idx_artifact_signal_requested_by ON public.artifact_signal USING btree (requested_by_user_id, occurred_at) WHERE (requested_by_user_id IS NOT NULL);

CREATE INDEX idx_artifact_signal_state_changed ON public.artifact_signal USING btree (state, state_changed_at);

CREATE INDEX idx_chat_message_thread_created ON public.chat_message USING btree (thread_id, created_at);

CREATE INDEX idx_chat_thread_workspace_id ON public.chat_thread USING btree (workspace_id);

CREATE INDEX idx_commit_contributor_commit_id ON public.commit_contributor USING btree (commit_id);

CREATE INDEX idx_commit_contributor_user_id ON public.commit_contributor USING btree (user_id);

CREATE INDEX idx_commit_file_change_commit_id ON public.commit_file_change USING btree (commit_id);

CREATE INDEX idx_commit_file_change_filename ON public.commit_file_change USING btree (filename);

CREATE INDEX idx_commit_pull_request_pr_id ON public.commit_pull_request USING btree (pull_request_id);

CREATE INDEX idx_delivery_policy_eval_feedback ON public.delivery_policy_evaluation USING btree (feedback_id, evaluated_at DESC);

CREATE INDEX idx_delivery_policy_eval_job ON public.delivery_policy_evaluation USING btree (agent_job_id, evaluated_at DESC);

CREATE INDEX idx_delivery_policy_eval_workspace ON public.delivery_policy_evaluation USING btree (workspace_id, evaluated_at DESC);

CREATE INDEX idx_discussion_author ON public.discussion USING btree (author_id);

CREATE INDEX idx_discussion_category ON public.discussion USING btree (category_id);

CREATE INDEX idx_discussion_category_repository ON public.discussion_category USING btree (repository_id);

CREATE INDEX idx_discussion_comment_author ON public.discussion_comment USING btree (author_id);

CREATE INDEX idx_discussion_comment_discussion ON public.discussion_comment USING btree (discussion_id);

CREATE INDEX idx_discussion_comment_discussion_created ON public.discussion_comment USING btree (discussion_id, created_at);

CREATE INDEX idx_discussion_comment_is_answer ON public.discussion_comment USING btree (is_answer);

CREATE INDEX idx_discussion_comment_parent ON public.discussion_comment USING btree (parent_comment_id);

CREATE INDEX idx_discussion_created_at ON public.discussion USING btree (created_at);

CREATE INDEX idx_discussion_repository ON public.discussion USING btree (repository_id);

CREATE INDEX idx_discussion_state ON public.discussion USING btree (state);

CREATE INDEX idx_feedback_agent_job ON public.feedback USING btree (agent_job_id);

CREATE INDEX idx_feedback_approval_workspace_decided ON public.feedback_approval USING btree (workspace_id, decided_at DESC);

CREATE INDEX idx_feedback_continuity ON public.feedback USING btree (thread_key);

CREATE INDEX idx_feedback_dispatch_projection ON public.feedback_dispatch USING btree (updated_at) WHERE ((projected_at IS NULL) AND ((state)::text = ANY (ARRAY[('SENT'::character varying)::text, ('SUPPRESSED'::character varying)::text, ('FAILED'::character varying)::text])));

CREATE INDEX idx_feedback_dispatch_recovery ON public.feedback_dispatch USING btree (state, lease_expires_at, updated_at);

CREATE INDEX idx_feedback_dispatch_workspace ON public.feedback_dispatch USING btree (workspace_id, created_at DESC);

CREATE INDEX idx_feedback_observation_observation ON public.feedback_observation USING btree (observation_id);

CREATE INDEX idx_feedback_placement_external_ref ON public.feedback_placement USING btree (posted_comment_ref);

CREATE INDEX idx_feedback_placement_feedback ON public.feedback_placement USING btree (feedback_id);

CREATE INDEX idx_feedback_recipient_created ON public.feedback USING btree (recipient_user_id, created_at DESC);

CREATE INDEX idx_feedback_replaces ON public.feedback USING btree (replaces_id);

CREATE INDEX idx_feedback_target ON public.feedback USING btree (artifact_kind, artifact_id);

CREATE INDEX idx_feedback_workspace ON public.feedback USING btree (workspace_id);

CREATE INDEX idx_feedback_workspace_created ON public.feedback USING btree (workspace_id, created_at DESC, id DESC);

CREATE INDEX idx_git_commit_author_id ON public.git_commit USING btree (author_id);

CREATE INDEX idx_git_commit_authored_at ON public.git_commit USING btree (authored_at);

CREATE INDEX idx_git_commit_committer_id ON public.git_commit USING btree (committer_id);

CREATE INDEX idx_git_commit_repository_id ON public.git_commit USING btree (repository_id);

CREATE INDEX idx_git_commit_unresolved_author_email ON public.git_commit USING btree (repository_id, author_email) WHERE ((author_id IS NULL) AND (author_email IS NOT NULL));

CREATE INDEX idx_git_commit_unresolved_committer_email ON public.git_commit USING btree (repository_id, committer_email) WHERE ((committer_id IS NULL) AND (committer_email IS NOT NULL));

CREATE INDEX idx_issue_author_id ON public.issue USING btree (author_id);

CREATE INDEX idx_issue_blocking_blocked_id ON public.issue_blocking USING btree (blocked_issue_id);

CREATE INDEX idx_issue_blocking_blocking_id ON public.issue_blocking USING btree (blocking_issue_id);

CREATE INDEX idx_issue_comment_author_id ON public.issue_comment USING btree (author_id);

CREATE INDEX idx_issue_comment_created_at ON public.issue_comment USING btree (created_at);

CREATE INDEX idx_issue_issue_type_id ON public.issue USING btree (issue_type_id);

CREATE INDEX idx_issue_parent_issue_id ON public.issue USING btree (parent_issue_id);

CREATE INDEX idx_issue_repository_id ON public.issue USING btree (repository_id);

CREATE INDEX idx_issue_state ON public.issue USING btree (state);

CREATE INDEX idx_issue_type_organization_id ON public.issue_type USING btree (organization_id);

CREATE INDEX idx_llm_model_connection ON public.llm_model USING btree (connection_id);

CREATE INDEX idx_llm_model_grant_workspace ON public.llm_model_workspace_grant USING btree (workspace_id);

CREATE INDEX idx_llm_model_price_model_from ON public.llm_model_price USING btree (model_id, effective_from);

CREATE INDEX idx_llm_usage_occurred_at ON public.llm_usage_event USING btree (occurred_at);

CREATE INDEX idx_llm_usage_ws_time ON public.llm_usage_event USING btree (workspace_id, occurred_at);

CREATE INDEX idx_observation_agent_job ON public.observation USING btree (agent_job_id);

CREATE INDEX idx_observation_correlation ON public.observation USING btree (recurrence_key);

CREATE INDEX idx_observation_practice_observed ON public.observation USING btree (practice_id, observed_at DESC);

CREATE INDEX idx_observation_subject ON public.observation USING btree (about_user_id);

CREATE INDEX idx_observation_target ON public.observation USING btree (artifact_kind, artifact_id);

CREATE INDEX idx_observation_target_run ON public.observation USING btree (artifact_kind, artifact_id, agent_job_id, observed_at DESC);

CREATE INDEX idx_observation_workspace ON public.observation USING btree (workspace_id);

CREATE INDEX idx_pr_review_author_id ON public.pull_request_review USING btree (author_id);

CREATE INDEX idx_pr_review_pull_request_id ON public.pull_request_review USING btree (pull_request_id);

CREATE INDEX idx_pr_review_submitted_at ON public.pull_request_review USING btree (submitted_at);

CREATE INDEX idx_practice_group_order ON public.practice USING btree (practice_group_id, display_order);

CREATE INDEX idx_practice_group_source_curated_slug ON public.practice_group USING btree (source_curated_slug) WHERE (source_curated_slug IS NOT NULL);

CREATE INDEX idx_practice_group_workspace_dashboard_visibility ON public.practice_group USING btree (workspace_id, visible_in_practice_dashboards);

CREATE INDEX idx_practice_practice_group ON public.practice USING btree (practice_group_id);

CREATE INDEX idx_practice_review_person_target_workspace ON public.practice_review_person_target USING btree (workspace_id);

CREATE INDEX idx_practice_review_repository_target_workspace ON public.practice_review_repository_target USING btree (workspace_id);

CREATE INDEX idx_practice_revision_practice ON public.practice_revision USING btree (practice_id);

CREATE INDEX idx_practice_source_curated_slug ON public.practice USING btree (source_curated_slug) WHERE (source_curated_slug IS NOT NULL);

CREATE INDEX idx_practice_workspace_autonomy ON public.practice USING btree (workspace_id, autonomy);

CREATE INDEX idx_project_creator_id ON public.project USING btree (creator_id);

CREATE INDEX idx_project_field_project_id ON public.project_field USING btree (project_id);

CREATE INDEX idx_project_field_value_field_id ON public.project_field_value USING btree (field_id);

CREATE INDEX idx_project_field_value_item_id ON public.project_field_value USING btree (item_id);

CREATE INDEX idx_project_item_content_type_archived ON public.project_item USING btree (project_id, content_type, archived);

CREATE INDEX idx_project_item_creator_id ON public.project_item USING btree (creator_id);

CREATE INDEX idx_project_item_issue_id ON public.project_item USING btree (issue_id);

CREATE INDEX idx_project_item_orphaned_relink ON public.project_item USING btree (content_database_id) WHERE ((issue_id IS NULL) AND (content_database_id IS NOT NULL) AND ((content_type)::text = ANY (ARRAY[('ISSUE'::character varying)::text, ('PULL_REQUEST'::character varying)::text])));

CREATE INDEX idx_project_item_project_archived ON public.project_item USING btree (project_id, archived);

CREATE INDEX idx_project_owner ON public.project USING btree (owner_type, owner_id);

CREATE INDEX idx_project_status_update_created_at ON public.project_status_update USING btree (project_id, created_at DESC);

CREATE INDEX idx_project_status_update_creator_id ON public.project_status_update USING btree (creator_id);

CREATE INDEX idx_pull_request_review_comment_thread ON public.pull_request_review_comment USING btree (thread_id);

CREATE INDEX idx_pull_request_review_thread_pull_request ON public.pull_request_review_thread USING btree (pull_request_id);

CREATE INDEX idx_pull_request_review_thread_resolved_by ON public.pull_request_review_thread USING btree (resolved_by_id);

CREATE INDEX idx_reaction_feedback_reactor ON public.reaction USING btree (feedback_id, reactor_user_id, created_at DESC);

CREATE INDEX idx_reaction_reactor_created ON public.reaction USING btree (reactor_user_id, created_at DESC);

CREATE INDEX idx_repository_name_with_owner ON public.repository USING btree (name_with_owner);

CREATE INDEX idx_repository_organization_id ON public.repository USING btree (organization_id);

CREATE INDEX idx_review_backfill_run_status ON public.review_backfill_run USING btree (status);

CREATE INDEX idx_review_backfill_run_sweep_schedule ON public.review_backfill_run USING btree (sweep_schedule_id) WHERE (sweep_schedule_id IS NOT NULL);

CREATE INDEX idx_review_backfill_run_workspace ON public.review_backfill_run USING btree (workspace_id, created_at);

CREATE INDEX idx_review_sweep_schedule_due ON public.review_sweep_schedule USING btree (enabled, next_run_at);

CREATE INDEX idx_slack_channel_consent_event_channel ON public.slack_channel_consent_event USING btree (workspace_id, slack_channel_id, created_at);

CREATE INDEX idx_slack_message_ingest ON public.slack_message USING btree (workspace_id, ingested_at);

CREATE INDEX idx_slack_message_thread ON public.slack_message USING btree (workspace_id, slack_channel_id, slack_thread_ts);

CREATE INDEX idx_slack_thread_participants ON public.slack_thread USING gin (participant_member_ids);

CREATE INDEX idx_survey_submission_account ON public.product_survey_submission USING btree (account_id);

CREATE INDEX idx_user_achievement_achievement ON public.user_achievement USING btree (achievement_id);

CREATE INDEX idx_user_achievement_user ON public.user_achievement USING btree (user_id);

CREATE INDEX idx_workspace_membership_user_id ON public.workspace_membership USING btree (user_id);

CREATE INDEX idx_workspace_slug_history_old_slug ON public.workspace_slug_history USING btree (old_slug);

CREATE INDEX idx_workspace_slug_history_redirect_expires_at ON public.workspace_slug_history USING btree (redirect_expires_at);

CREATE INDEX idx_workspace_slug_history_workspace_id ON public.workspace_slug_history USING btree (workspace_id);

CREATE INDEX idx_workspace_team_label_filter_team_id ON public.workspace_team_label_filter USING btree (team_id);

CREATE INDEX idx_workspace_team_repo_settings_team_repo ON public.workspace_team_repository_settings USING btree (team_id, repository_id);

CREATE INDEX idx_workspace_team_settings_team_id ON public.workspace_team_settings USING btree (team_id);

CREATE INDEX idx_ws_llm_model_connection ON public.workspace_llm_model USING btree (connection_id);

CREATE INDEX ix_account_export_account ON public.account_export USING btree (account_id);

CREATE INDEX ix_account_export_ready_expiry ON public.account_export USING btree (expires_at) WHERE ((status)::text = 'READY'::text);

CREATE INDEX ix_account_status_nonactive ON public.account USING btree (status) WHERE ((status)::text <> 'ACTIVE'::text);

CREATE INDEX ix_agent_job_delivery_pending ON public.agent_job USING btree (completed_at) WHERE (((status)::text = 'COMPLETED'::text) AND ((delivery_status)::text = 'PENDING'::text));

CREATE INDEX ix_agent_job_queued_available ON public.agent_job USING btree (available_at, id) WHERE ((status)::text = 'QUEUED'::text);

CREATE INDEX ix_agent_job_retention ON public.agent_job USING btree (completed_at) WHERE ((status)::text = ANY (ARRAY[('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('TIMED_OUT'::character varying)::text, ('CANCELLED'::character varying)::text]));

CREATE INDEX ix_agent_job_running_owner ON public.agent_job USING btree (worker_id) WHERE ((status)::text = 'RUNNING'::text);

CREATE INDEX ix_agent_job_running_purpose ON public.agent_job USING btree (workspace_id, purpose) WHERE ((status)::text = 'RUNNING'::text);

CREATE INDEX ix_auth_event_account_occurred ON public.auth_event USING btree (account_id, occurred_at DESC) WHERE (account_id IS NOT NULL);

CREATE INDEX ix_auth_event_failure_ip ON public.auth_event USING btree (ip_inet) WHERE ((result)::text = 'FAILURE'::text);

CREATE INDEX ix_auth_event_occurred ON public.auth_event USING btree (occurred_at);

CREATE INDEX ix_auth_rate_limit_bucket_expires_at ON public.auth_rate_limit_bucket USING btree (expires_at);

CREATE INDEX ix_config_audit_event_actor ON public.config_audit_event USING btree (actor_account_id, occurred_at DESC) WHERE (actor_account_id IS NOT NULL);

CREATE INDEX ix_config_audit_event_changed_keys ON public.config_audit_event USING gin (changed_keys);

CREATE INDEX ix_config_audit_event_entity ON public.config_audit_event USING btree (workspace_id, entity_type, entity_id, occurred_at DESC, id DESC);

CREATE INDEX ix_config_audit_event_occurred ON public.config_audit_event USING btree (occurred_at DESC, id DESC);

CREATE INDEX ix_config_audit_event_workspace_occurred ON public.config_audit_event USING btree (workspace_id, occurred_at DESC, id DESC);

CREATE INDEX ix_connection_audit_lookup ON public.connection_audit USING btree (connection_id, occurred_at DESC);

CREATE INDEX ix_connection_credential_rotation ON public.connection USING btree (credentials_key_version, id) WHERE (credentials_encrypted IS NOT NULL);

CREATE INDEX ix_connection_outline_subscription ON public.connection USING btree (((config ->> 'webhookSubscriptionId'::text))) WHERE (((kind)::text = 'OUTLINE'::text) AND ((state)::text = 'ACTIVE'::text));

CREATE INDEX ix_connection_workspace_kind ON public.connection USING btree (workspace_id, kind);

CREATE INDEX ix_consent_decision_latest ON public.consent_decision USING btree (account_id, purpose, occurred_at DESC, id DESC);

CREATE INDEX ix_identity_link_account ON public.identity_link USING btree (account_id);

CREATE INDEX ix_identity_link_external_actor ON public.identity_link USING btree (external_actor_id) WHERE (external_actor_id IS NOT NULL);

CREATE INDEX ix_issue_repository_live ON public.issue USING btree (repository_id, issue_type) WHERE (deleted_at IS NULL);

CREATE INDEX ix_issued_jwt_account_active ON public.issued_jwt USING btree (account_id) WHERE (revoked_at IS NULL);

CREATE INDEX ix_issued_jwt_expires_at ON public.issued_jwt USING btree (expires_at);

CREATE INDEX ix_jwt_signing_key_active ON public.jwt_signing_key USING btree (active) WHERE (active = true);

CREATE INDEX ix_oauth_state_nonce_issued_at ON public.oauth_state_nonce USING btree (issued_at);

CREATE INDEX ix_outline_collection_pending_sync ON public.outline_collection USING btree (workspace_id) WHERE (((state)::text = 'ENABLED'::text) AND ((sync_status)::text = 'PENDING'::text));

CREATE INDEX ix_outline_document_collection ON public.outline_document USING btree (workspace_id, collection_id);

CREATE INDEX ix_outline_document_event_document ON public.outline_document_event USING btree (workspace_id, document_id, occurred_at);

CREATE INDEX ix_outline_document_eviction ON public.outline_document USING btree (workspace_id, last_materialized_at NULLS FIRST) WHERE (body_markdown IS NOT NULL);

CREATE INDEX ix_outline_document_fts ON public.outline_document USING gin (to_tsvector('simple'::regconfig, (((COALESCE(title, ''::character varying))::text || ' '::text) || "left"(COALESCE(body_markdown, ''::text), 900000))));

CREATE INDEX ix_outline_document_projection ON public.outline_document USING btree (workspace_id, deleted_at NULLS FIRST, outline_updated_at DESC NULLS LAST, id);

CREATE INDEX ix_outline_document_slug ON public.outline_document USING btree (workspace_id, slug);

CREATE INDEX ix_sync_job_connection_created ON public.sync_job USING btree (connection_id, created_at DESC);

CREATE INDEX ix_worker_registry_last_heartbeat ON public.worker_registry USING btree (last_heartbeat);

CREATE INDEX ix_worker_token_denylist_expires_at ON public.worker_token_denylist USING btree (expires_at);

CREATE UNIQUE INDEX uk_agent_job_idempotency ON public.agent_job USING btree (workspace_id, idempotency_key) WHERE (((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text])) AND (idempotency_key IS NOT NULL));

CREATE UNIQUE INDEX uk_agent_job_token_hash ON public.agent_job USING btree (job_token_hash);

CREATE UNIQUE INDEX uk_user_provider_login ON public."user" USING btree (provider_id, lower((login)::text));

CREATE UNIQUE INDEX uq_account_export_in_flight ON public.account_export USING btree (account_id) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROCESSING'::character varying)::text]));

CREATE UNIQUE INDEX uq_account_primary_email_active ON public.account USING btree (primary_email) WHERE ((primary_email IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uq_connection_audit_idempotency ON public.connection_audit USING btree (connection_id, event_type, correlation_id) NULLS NOT DISTINCT;

CREATE UNIQUE INDEX uq_connection_one_active_scm_per_workspace ON public.connection USING btree (workspace_id) WHERE (((state)::text = 'ACTIVE'::text) AND ((kind)::text = ANY (ARRAY[('GITHUB'::character varying)::text, ('GITLAB'::character varying)::text])));

CREATE UNIQUE INDEX uq_connection_one_active_slack_per_team ON public.connection USING btree (instance_key) WHERE (((state)::text = 'ACTIVE'::text) AND ((kind)::text = 'SLACK'::text));

CREATE UNIQUE INDEX uq_connection_one_active_slack_per_workspace ON public.connection USING btree (workspace_id) WHERE (((state)::text = 'ACTIVE'::text) AND ((kind)::text = 'SLACK'::text));

CREATE UNIQUE INDEX uq_connection_pending ON public.connection USING btree (workspace_id, kind) WHERE (instance_key IS NULL);

CREATE UNIQUE INDEX uq_identity_link_active_per_provider ON public.identity_link USING btree (account_id, provider_id, COALESCE(team_id, ''::character varying)) WHERE (disabled_at IS NULL);

CREATE UNIQUE INDEX uq_identity_link_provider_subject_team ON public.identity_link USING btree (provider_id, subject, COALESCE(team_id, ''::character varying));

CREATE UNIQUE INDEX uq_review_backfill_run_active ON public.review_backfill_run USING btree (workspace_id) WHERE ((status)::text = ANY (ARRAY[('RUNNING'::character varying)::text, ('PAUSED'::character varying)::text]));

CREATE UNIQUE INDEX ux_chat_message_in_flight_v2 ON public.chat_message USING btree (thread_id) WHERE ((status)::text = 'in_flight'::text);

CREATE UNIQUE INDEX ux_llm_model_price_open ON public.llm_model_price USING btree (model_id) WHERE (effective_to IS NULL);

CREATE UNIQUE INDEX ux_sync_job_active ON public.sync_job USING btree (connection_id) WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('RUNNING'::character varying)::text]));

CREATE CONSTRAINT TRIGGER practice_requires_current_revision_projection AFTER INSERT OR UPDATE OF current_revision_id, slug, name, applies_to, bindings, criteria, precompute_script, automated_review_policy, why_it_matters, what_good_looks_like, practice_group_id ON public.practice DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.enforce_practice_current_revision_projection();

CREATE TRIGGER practice_revision_immutable_update BEFORE UPDATE ON public.practice_revision FOR EACH ROW EXECUTE FUNCTION public.enforce_practice_revision_immutability();

CREATE TRIGGER trg_consent_decision_append_only BEFORE DELETE OR UPDATE ON public.consent_decision FOR EACH ROW EXECUTE FUNCTION public.enforce_consent_decision_append_only();

CREATE TRIGGER trg_consent_notice_immutable BEFORE DELETE OR UPDATE ON public.consent_notice FOR EACH ROW EXECUTE FUNCTION public.enforce_consent_notice_immutable();

ALTER TABLE ONLY public.team_repository_permission
    ADD CONSTRAINT "FK7qxvqq8p6690vtdux47lsg8b1" FOREIGN KEY (team_id) REFERENCES public.team(id);

ALTER TABLE ONLY public.chat_message
    ADD CONSTRAINT "FK8s34d909gxc4xrlvml8gag9kh" FOREIGN KEY (thread_id) REFERENCES public.chat_thread(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.team_repository_permission
    ADD CONSTRAINT "FK92gtctw6ca02527qjja7gns9f" FOREIGN KEY (repository_id) REFERENCES public.repository(id);

ALTER TABLE ONLY public.chat_message
    ADD CONSTRAINT "FKd0fewjs0l68rq2bww9h8o4cmb" FOREIGN KEY (parent_message_id) REFERENCES public.chat_message(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.repository_to_monitor
    ADD CONSTRAINT "FKdkxnkm4a2wyw0d5k63gh2st64" FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.chat_thread
    ADD CONSTRAINT "FKikdxlx9viomcwrgxj7fbyfsew" FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.team_membership
    ADD CONSTRAINT "FKnkpwi3whks92uvhn5qe71v4k6" FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.team_membership
    ADD CONSTRAINT "FKrf92vmiawfvyhxcmigcg10opm" FOREIGN KEY (team_id) REFERENCES public.team(id);

ALTER TABLE ONLY public.label
    ADD CONSTRAINT fk2951edbl9g9y8ee1q97e2ff75 FOREIGN KEY (repository_id) REFERENCES public.repository(id);

ALTER TABLE ONLY public.issue_assignee
    ADD CONSTRAINT fk2cfu8w8wjb9vosy4hbrme0rqe FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.pull_request_requested_reviewers
    ADD CONSTRAINT fk6dld06xx8rh9xhqfnca070a0i FOREIGN KEY (pull_request_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fk76s4b6ncspm9bk35y49xh4s9t FOREIGN KEY (repository_id) REFERENCES public.repository(id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fk7t1o4tuel06m9bn4dppqmiod6 FOREIGN KEY (milestone_id) REFERENCES public.milestone(id);

ALTER TABLE ONLY public.issue_comment
    ADD CONSTRAINT fk8wy5rxggrte2ntcq80g7o7210 FOREIGN KEY (issue_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.activity_event
    ADD CONSTRAINT fk_activity_event_actor FOREIGN KEY (actor_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.activity_event
    ADD CONSTRAINT fk_activity_event_repository FOREIGN KEY (repository_id) REFERENCES public.repository(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.activity_event
    ADD CONSTRAINT fk_activity_event_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.agent_job
    ADD CONSTRAINT fk_agent_job_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.artifact_signal
    ADD CONSTRAINT fk_artifact_signal_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.chat_message_vote
    ADD CONSTRAINT fk_chat_message_vote_message FOREIGN KEY (message_id) REFERENCES public.chat_message(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.chat_thread
    ADD CONSTRAINT fk_chat_thread_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.commit_contributor
    ADD CONSTRAINT fk_commit_contributor_commit FOREIGN KEY (commit_id) REFERENCES public.git_commit(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.commit_contributor
    ADD CONSTRAINT fk_commit_contributor_user FOREIGN KEY (user_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.commit_file_change
    ADD CONSTRAINT fk_commit_file_change_commit FOREIGN KEY (commit_id) REFERENCES public.git_commit(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.commit_pull_request
    ADD CONSTRAINT fk_commit_pr_commit FOREIGN KEY (commit_id) REFERENCES public.git_commit(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.commit_pull_request
    ADD CONSTRAINT fk_commit_pr_pull_request FOREIGN KEY (pull_request_id) REFERENCES public.issue(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.connection_activity
    ADD CONSTRAINT fk_connection_activity_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.connection_activity
    ADD CONSTRAINT fk_connection_activity_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.connection_audit
    ADD CONSTRAINT fk_connection_audit_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id);

ALTER TABLE ONLY public.connection
    ADD CONSTRAINT fk_connection_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.consent_decision
    ADD CONSTRAINT fk_consent_decision_account FOREIGN KEY (account_id) REFERENCES public.account(id);

ALTER TABLE ONLY public.discussion
    ADD CONSTRAINT fk_discussion_answer_chosen_by FOREIGN KEY (answer_chosen_by_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.discussion
    ADD CONSTRAINT fk_discussion_answer_comment FOREIGN KEY (answer_comment_id) REFERENCES public.discussion_comment(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.discussion
    ADD CONSTRAINT fk_discussion_author FOREIGN KEY (author_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.discussion
    ADD CONSTRAINT fk_discussion_category FOREIGN KEY (category_id) REFERENCES public.discussion_category(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.discussion_category
    ADD CONSTRAINT fk_discussion_category_repository FOREIGN KEY (repository_id) REFERENCES public.repository(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.discussion_comment
    ADD CONSTRAINT fk_discussion_comment_author FOREIGN KEY (author_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.discussion_comment
    ADD CONSTRAINT fk_discussion_comment_discussion FOREIGN KEY (discussion_id) REFERENCES public.discussion(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.discussion_comment
    ADD CONSTRAINT fk_discussion_comment_parent FOREIGN KEY (parent_comment_id) REFERENCES public.discussion_comment(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.discussion_comment
    ADD CONSTRAINT fk_discussion_comment_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.discussion_label
    ADD CONSTRAINT fk_discussion_label_discussion FOREIGN KEY (discussion_id) REFERENCES public.discussion(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.discussion_label
    ADD CONSTRAINT fk_discussion_label_label FOREIGN KEY (label_id) REFERENCES public.label(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.discussion
    ADD CONSTRAINT fk_discussion_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.discussion
    ADD CONSTRAINT fk_discussion_repository FOREIGN KEY (repository_id) REFERENCES public.repository(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_observation
    ADD CONSTRAINT fk_feedback_observation_feedback FOREIGN KEY (feedback_id) REFERENCES public.feedback(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_observation
    ADD CONSTRAINT fk_feedback_observation_observation FOREIGN KEY (observation_id) REFERENCES public.observation(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_placement
    ADD CONSTRAINT fk_feedback_placement_feedback FOREIGN KEY (feedback_id) REFERENCES public.feedback(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.git_commit
    ADD CONSTRAINT fk_git_commit_author FOREIGN KEY (author_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.git_commit
    ADD CONSTRAINT fk_git_commit_committer FOREIGN KEY (committer_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.git_commit
    ADD CONSTRAINT fk_git_commit_repository FOREIGN KEY (repository_id) REFERENCES public.repository(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.identity_link
    ADD CONSTRAINT fk_identity_link_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_blocking
    ADD CONSTRAINT fk_issue_blocking_blocked FOREIGN KEY (blocked_issue_id) REFERENCES public.issue(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_blocking
    ADD CONSTRAINT fk_issue_blocking_blocking FOREIGN KEY (blocking_issue_id) REFERENCES public.issue(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.issue_comment
    ADD CONSTRAINT fk_issue_comment_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fk_issue_issue_type FOREIGN KEY (issue_type_id) REFERENCES public.issue_type(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fk_issue_parent_issue FOREIGN KEY (parent_issue_id) REFERENCES public.issue(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fk_issue_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.issue_type
    ADD CONSTRAINT fk_issue_type_organization FOREIGN KEY (organization_id) REFERENCES public.organization(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.label
    ADD CONSTRAINT fk_label_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.llm_model
    ADD CONSTRAINT fk_llm_model_connection FOREIGN KEY (connection_id) REFERENCES public.llm_connection(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.llm_model_price
    ADD CONSTRAINT fk_llm_model_price_model FOREIGN KEY (model_id) REFERENCES public.llm_model(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.llm_usage_event
    ADD CONSTRAINT fk_llm_usage_event_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT fk_milestone_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.observation
    ADD CONSTRAINT fk_observation_practice FOREIGN KEY (practice_id) REFERENCES public.practice(id);

ALTER TABLE ONLY public.observation
    ADD CONSTRAINT fk_observation_practice_workspace FOREIGN KEY (practice_id, workspace_id) REFERENCES public.practice(id, workspace_id);

ALTER TABLE ONLY public.observation
    ADD CONSTRAINT fk_observation_revision FOREIGN KEY (practice_revision_id) REFERENCES public.practice_revision(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT fk_organization_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fk_pr_review_comment_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fk_pr_review_comment_reply FOREIGN KEY (in_reply_to_id) REFERENCES public.pull_request_review_comment(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fk_pr_review_comment_thread FOREIGN KEY (thread_id) REFERENCES public.pull_request_review_thread(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.pull_request_review
    ADD CONSTRAINT fk_pr_review_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT fk_pr_review_thread_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT fk_pr_review_thread_pull_request FOREIGN KEY (pull_request_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT fk_pr_review_thread_root_comment FOREIGN KEY (root_comment_id) REFERENCES public.pull_request_review_comment(id);

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT fk_practice_current_revision FOREIGN KEY (current_revision_id) REFERENCES public.practice_revision(id);

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT fk_practice_group FOREIGN KEY (practice_group_id) REFERENCES public.practice_group(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.practice_group
    ADD CONSTRAINT fk_practice_group_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.practice_revision
    ADD CONSTRAINT fk_practice_revision_practice FOREIGN KEY (practice_id) REFERENCES public.practice(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.practice
    ADD CONSTRAINT fk_practice_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.project
    ADD CONSTRAINT fk_project_creator FOREIGN KEY (creator_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.project_field
    ADD CONSTRAINT fk_project_field_project FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.project_field_value
    ADD CONSTRAINT fk_project_field_value_field FOREIGN KEY (field_id) REFERENCES public.project_field(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.project_field_value
    ADD CONSTRAINT fk_project_field_value_item FOREIGN KEY (item_id) REFERENCES public.project_item(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.project_item
    ADD CONSTRAINT fk_project_item_creator FOREIGN KEY (creator_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.project_item
    ADD CONSTRAINT fk_project_item_issue FOREIGN KEY (issue_id) REFERENCES public.issue(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.project_item
    ADD CONSTRAINT fk_project_item_project FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.project_item
    ADD CONSTRAINT fk_project_item_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.project
    ADD CONSTRAINT fk_project_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.project_status_update
    ADD CONSTRAINT fk_project_status_update_creator FOREIGN KEY (creator_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.project_status_update
    ADD CONSTRAINT fk_project_status_update_project FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.project_status_update
    ADD CONSTRAINT fk_project_status_update_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.pull_request_review_thread
    ADD CONSTRAINT fk_pull_request_review_thread_resolved_by FOREIGN KEY (resolved_by_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.reaction
    ADD CONSTRAINT fk_reaction_feedback FOREIGN KEY (feedback_id) REFERENCES public.feedback(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.repository_collaborator
    ADD CONSTRAINT fk_repository_collaborator_repository FOREIGN KEY (repository_id) REFERENCES public.repository(id);

ALTER TABLE ONLY public.repository_collaborator
    ADD CONSTRAINT fk_repository_collaborator_user FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.repository
    ADD CONSTRAINT fk_repository_organization FOREIGN KEY (organization_id) REFERENCES public.organization(id);

ALTER TABLE ONLY public.repository
    ADD CONSTRAINT fk_repository_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.review_backfill_run
    ADD CONSTRAINT fk_review_backfill_run_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.review_sweep_schedule
    ADD CONSTRAINT fk_review_sweep_schedule_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.sync_job
    ADD CONSTRAINT fk_sync_job_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id);

ALTER TABLE ONLY public.sync_job
    ADD CONSTRAINT fk_sync_job_connection_workspace FOREIGN KEY (connection_id, workspace_id) REFERENCES public.connection(id, workspace_id);

ALTER TABLE ONLY public.sync_job
    ADD CONSTRAINT fk_sync_job_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.team
    ADD CONSTRAINT fk_team_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.user_achievement
    ADD CONSTRAINT fk_user_achievement_user FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.user_preferences
    ADD CONSTRAINT fk_user_preferences_user FOREIGN KEY (user_id) REFERENCES public."user"(id) ON DELETE CASCADE;

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT fk_user_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT fk_workspace_agent_binding_instance_model FOREIGN KEY (instance_model_id) REFERENCES public.llm_model(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT fk_workspace_agent_binding_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT fk_workspace_agent_binding_workspace_model FOREIGN KEY (workspace_model_id) REFERENCES public.workspace_llm_model(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.workspace_membership
    ADD CONSTRAINT fk_workspace_membership_user FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.workspace_membership
    ADD CONSTRAINT fk_workspace_membership_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.workspace
    ADD CONSTRAINT fk_workspace_organization FOREIGN KEY (organization_id) REFERENCES public.organization(id);

ALTER TABLE ONLY public.workspace_slug_history
    ADD CONSTRAINT fk_workspace_slug_history_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_label_filter
    ADD CONSTRAINT fk_workspace_team_label_filter_label FOREIGN KEY (label_id) REFERENCES public.label(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_label_filter
    ADD CONSTRAINT fk_workspace_team_label_filter_team FOREIGN KEY (team_id) REFERENCES public.team(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_label_filter
    ADD CONSTRAINT fk_workspace_team_label_filter_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_repository_settings
    ADD CONSTRAINT fk_workspace_team_repo_settings_repository FOREIGN KEY (repository_id) REFERENCES public.repository(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_repository_settings
    ADD CONSTRAINT fk_workspace_team_repo_settings_team FOREIGN KEY (team_id) REFERENCES public.team(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_repository_settings
    ADD CONSTRAINT fk_workspace_team_repo_settings_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_settings
    ADD CONSTRAINT fk_workspace_team_settings_team FOREIGN KEY (team_id) REFERENCES public.team(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_team_settings
    ADD CONSTRAINT fk_workspace_team_settings_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_llm_connection
    ADD CONSTRAINT fk_ws_llm_connection_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT fk_ws_llm_model_connection FOREIGN KEY (connection_id) REFERENCES public.workspace_llm_connection(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT fk_ws_llm_model_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT fkbjhs37s6qmqtd330gu9mit6w0 FOREIGN KEY (repository_id) REFERENCES public.repository(id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fkbx1g5jpdegymhyv9pbk2jdgfw FOREIGN KEY (review_id) REFERENCES public.pull_request_review(id);

ALTER TABLE ONLY public.issue_comment
    ADD CONSTRAINT fkdy6oeojymud1wna20olqgyt31 FOREIGN KEY (author_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.pull_request_review
    ADD CONSTRAINT fkeehfcwrodfu61gremlcvhgir5 FOREIGN KEY (author_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.milestone
    ADD CONSTRAINT fkg6ieho7gomiumy85puy6l13f1 FOREIGN KEY (creator_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.pull_request_review
    ADD CONSTRAINT fkio96gq2jetvy6a4in9nl8vkvd FOREIGN KEY (pull_request_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.pull_request_requested_reviewers
    ADD CONSTRAINT fkioq4g5aksr97l6qyl4g5l63tn FOREIGN KEY (user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.issue_label
    ADD CONSTRAINT fkit5n9c0frugu5m8xqsxtps63r FOREIGN KEY (issue_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.issue_assignee
    ADD CONSTRAINT fkocgmsva4p5e8ic9k5dbjqa15u FOREIGN KEY (issue_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fkohqvdiswptbm0h8cniq7r1tgq FOREIGN KEY (pull_request_id) REFERENCES public.issue(id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fkqvnu6vslj5txt8xencru8m6x4 FOREIGN KEY (merged_by_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.issue
    ADD CONSTRAINT fkrwr6v8fiqetuiuvfjcvie8s85 FOREIGN KEY (author_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.pull_request_review_comment
    ADD CONSTRAINT fktl08ieowbl171xem2bciho7kw FOREIGN KEY (author_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.issue_label
    ADD CONSTRAINT fkxbk5rr30kkb6k4ech7x4vh9h FOREIGN KEY (label_id) REFERENCES public.label(id);

ALTER TABLE ONLY public.account_export
    ADD CONSTRAINT sfk_account_export_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.account_feature
    ADD CONSTRAINT sfk_account_feature_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.artifact_signal
    ADD CONSTRAINT sfk_artifact_signal_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_account_id FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_acting_account_id FOREIGN KEY (acting_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_git_provider_id FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_identity_link_id FOREIGN KEY (identity_link_id) REFERENCES public.identity_link(id) ON DELETE SET NULL;

ALTER TABLE public.auth_event
    ADD CONSTRAINT sfk_auth_event_workspace_id FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.config_audit_event
    ADD CONSTRAINT sfk_config_audit_event_acting_account_id FOREIGN KEY (acting_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.config_audit_event
    ADD CONSTRAINT sfk_config_audit_event_actor_account_id FOREIGN KEY (actor_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.config_audit_event
    ADD CONSTRAINT sfk_config_audit_event_workspace_id FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.consent_decision
    ADD CONSTRAINT sfk_consent_decision_notice FOREIGN KEY (notice_version, notice_sha256) REFERENCES public.consent_notice(version, sha256);

ALTER TABLE ONLY public.delivery_policy_evaluation
    ADD CONSTRAINT sfk_delivery_policy_eval_feedback FOREIGN KEY (workspace_id, feedback_id) REFERENCES public.feedback(workspace_id, id) ON DELETE SET NULL (feedback_id);

ALTER TABLE ONLY public.delivery_policy_evaluation
    ADD CONSTRAINT sfk_delivery_policy_eval_job FOREIGN KEY (workspace_id, agent_job_id) REFERENCES public.agent_job(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.delivery_policy_evaluation
    ADD CONSTRAINT sfk_delivery_policy_eval_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT sfk_feedback_agent_job FOREIGN KEY (agent_job_id) REFERENCES public.agent_job(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.feedback_dispatch
    ADD CONSTRAINT sfk_feedback_dispatch_feedback FOREIGN KEY (workspace_id, feedback_id) REFERENCES public.feedback(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_dispatch
    ADD CONSTRAINT sfk_feedback_dispatch_job FOREIGN KEY (workspace_id, agent_job_id) REFERENCES public.agent_job(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_dispatch
    ADD CONSTRAINT sfk_feedback_dispatch_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_placement
    ADD CONSTRAINT sfk_feedback_placement_chat_message FOREIGN KEY (chat_message_id) REFERENCES public.chat_message(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT sfk_feedback_recipient FOREIGN KEY (recipient_user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT sfk_feedback_replaces FOREIGN KEY (replaces_id) REFERENCES public.feedback(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT sfk_feedback_subject FOREIGN KEY (about_user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT sfk_feedback_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.identity_link
    ADD CONSTRAINT sfk_identity_link_provider FOREIGN KEY (provider_id) REFERENCES public.identity_provider(id);

ALTER TABLE ONLY public.issued_jwt
    ADD CONSTRAINT sfk_issued_jwt_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.llm_model_workspace_grant
    ADD CONSTRAINT sfk_llm_model_grant_model FOREIGN KEY (model_id) REFERENCES public.llm_model(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.llm_model_workspace_grant
    ADD CONSTRAINT sfk_llm_model_grant_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.mentor_slack_thread
    ADD CONSTRAINT sfk_mentor_slack_thread_chat_thread FOREIGN KEY (chat_thread_id) REFERENCES public.chat_thread(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.mentor_slack_thread
    ADD CONSTRAINT sfk_mentor_slack_thread_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.observation
    ADD CONSTRAINT sfk_observation_agent_job FOREIGN KEY (agent_job_id) REFERENCES public.agent_job(id) ON DELETE RESTRICT NOT VALID;

ALTER TABLE ONLY public.observation
    ADD CONSTRAINT sfk_observation_subject FOREIGN KEY (about_user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.observation
    ADD CONSTRAINT sfk_observation_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.outline_collection
    ADD CONSTRAINT sfk_outline_collection_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id);

ALTER TABLE ONLY public.outline_collection
    ADD CONSTRAINT sfk_outline_collection_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.outline_document
    ADD CONSTRAINT sfk_outline_document_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id);

ALTER TABLE ONLY public.outline_document_event
    ADD CONSTRAINT sfk_outline_document_event_connection FOREIGN KEY (connection_id) REFERENCES public.connection(id);

ALTER TABLE ONLY public.outline_document_event
    ADD CONSTRAINT sfk_outline_document_event_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.outline_document
    ADD CONSTRAINT sfk_outline_document_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.practice_catalog_installation
    ADD CONSTRAINT sfk_practice_catalog_installation_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.practice_review_person_target
    ADD CONSTRAINT sfk_practice_review_person_target_membership FOREIGN KEY (workspace_id, user_id) REFERENCES public.workspace_membership(workspace_id, user_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.practice_review_repository_target
    ADD CONSTRAINT sfk_practice_review_repository_target_monitor FOREIGN KEY (workspace_id, repository_monitor_id) REFERENCES public.repository_to_monitor(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.practice_review_repository_target
    ADD CONSTRAINT sfk_practice_review_repository_target_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.product_feedback
    ADD CONSTRAINT sfk_product_feedback_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.product_feedback
    ADD CONSTRAINT sfk_product_feedback_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.product_survey
    ADD CONSTRAINT sfk_product_survey_creator FOREIGN KEY (created_by_account_id) REFERENCES public.account(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.product_survey
    ADD CONSTRAINT sfk_product_survey_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.reaction
    ADD CONSTRAINT sfk_reaction_reactor FOREIGN KEY (reactor_user_id) REFERENCES public."user"(id);

ALTER TABLE ONLY public.slack_channel_consent_event
    ADD CONSTRAINT sfk_slack_channel_consent_event_actor FOREIGN KEY (actor_user_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.slack_channel_consent_event
    ADD CONSTRAINT sfk_slack_channel_consent_event_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.slack_message
    ADD CONSTRAINT sfk_slack_message_author_member FOREIGN KEY (author_member_id) REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.slack_message
    ADD CONSTRAINT sfk_slack_message_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.slack_monitored_channel
    ADD CONSTRAINT sfk_slack_monitored_channel_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.slack_participant_consent
    ADD CONSTRAINT sfk_slack_participant_consent_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.slack_thread
    ADD CONSTRAINT sfk_slack_thread_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id);

ALTER TABLE ONLY public.product_survey_submission
    ADD CONSTRAINT sfk_survey_submission_account FOREIGN KEY (account_id) REFERENCES public.account(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.product_survey_submission
    ADD CONSTRAINT sfk_survey_submission_survey FOREIGN KEY (survey_id) REFERENCES public.product_survey(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.product_survey_submission
    ADD CONSTRAINT sfk_survey_submission_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspace(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_agent_binding
    ADD CONSTRAINT sfk_workspace_agent_binding_workspace_model_scope FOREIGN KEY (workspace_model_id, workspace_id) REFERENCES public.workspace_llm_model(id, workspace_id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.workspace_llm_model
    ADD CONSTRAINT sfk_ws_llm_model_connection_scope FOREIGN KEY (connection_id, workspace_id) REFERENCES public.workspace_llm_connection(id, workspace_id) ON DELETE CASCADE;
