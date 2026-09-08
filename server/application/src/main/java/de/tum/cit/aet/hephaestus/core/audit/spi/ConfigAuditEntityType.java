package de.tum.cit.aet.hephaestus.core.audit.spi;

/**
 * The kind of admin-configurable resource a {@code config_audit_event} row describes.
 *
 * <p>Mirrored by the {@code ck_config_audit_event_entity_type} CHECK constraint;
 * {@code ConfigAuditImmutabilityIntegrationTest} reads {@code pg_constraint} on the migrated schema and
 * fails if the two drift. Widening is a changeset that drops and re-adds the constraint.
 *
 * <p>Values marked historical are no longer written but must stay: the table is append-only, so
 * rewriting an old row to the current spelling is the exact mutation the immutability trigger refuses.
 */
public enum ConfigAuditEntityType {
    PRACTICE_REVIEW_SETTINGS,
    /** Which model, with what limits, runs practice reviews / the mentor for a workspace. */
    AGENT_BINDING,
    /** Historical only — an earlier spelling of {@link #AGENT_BINDING}. */
    AGENT_CONFIG,
    /** Historical only — an earlier spelling of {@link #AGENT_BINDING}. */
    AI_CONFIG_BINDING,
    /**
     * A member's role or roster visibility: admin-initiated changes, plus role changes org sync applies
     * (actor {@code SYSTEM}). Memberships org sync itself creates or removes are excluded as roster churn
     * that would bury the admin-initiated rows.
     */
    WORKSPACE_ROLE,
    WORKSPACE_ACCESS_POLICY,
    WORKSPACE_ACCESS_REQUEST,
    DIRECTORY_POLICY,
    GITHUB_ACCESS_POLICY,
    GITHUB_ACCESS_MEMBERSHIP,
    WORKSPACE_FEATURES,
    WORKSPACE_STATUS,
    /** The workspace's stored SCM access token (rotation only — the value is never recorded). */
    WORKSPACE_TOKEN,
    WORKSPACE_VISIBILITY,

    /** Historical only — the earlier spelling of {@link #PRACTICE_USAGE}. */
    PRACTICE_ACTIVE,
    PRACTICE_USAGE,
    /** A practice's review definition, excluding its active state and catalog placement. */
    PRACTICE_DEFINITION,
    PRACTICE_GROUP,
    CURATED_PRACTICE,
    CURATED_PRACTICE_GROUP,

    /** A workspace's monthly cap on HOST-funded LLM spend. Set by instance admins. */
    WORKSPACE_INSTANCE_LLM_BUDGET,
    /** A workspace's own cap on spend through its own connected provider. Set by its own admins. */
    WORKSPACE_OWN_PROVIDER_LLM_BUDGET,
    /** Historical only — the earlier spelling of {@link #WORKSPACE_INSTANCE_LLM_BUDGET}. */
    WORKSPACE_LLM_BUDGET,
    /** Historical only — the earlier spelling of {@link #WORKSPACE_OWN_PROVIDER_LLM_BUDGET}. */
    WORKSPACE_BYO_LLM_BUDGET,

    /**
     * A backfill campaign, which can commit a month's LLM budget in a single request; who authorised it
     * must be answerable from the record.
     */
    REVIEW_BACKFILL_RUN,

    /** A standing instruction to review recent work on a cadence — authorised once, spends every night. */
    REVIEW_SWEEP_SCHEDULE,

    WORKSPACE_LLM_CONNECTION,
    /** A model on a workspace's own BYO connection, including its inline price and enablement. */
    WORKSPACE_LLM_MODEL,
}
