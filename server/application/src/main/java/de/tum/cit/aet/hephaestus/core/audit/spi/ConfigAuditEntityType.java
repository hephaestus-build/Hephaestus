package de.tum.cit.aet.hephaestus.core.audit.spi;

/** Enum values must match the database CHECK; retained append-only audit rows still require removed resource kinds. */
public enum ConfigAuditEntityType {
    PROVIDER_RESOURCE_RELEASE,
    PRACTICE_REVIEW_SETTINGS,
    /** Which model, with what limits, runs practice reviews / the conversation for a workspace. */
    MODEL_ASSIGNMENT,
    /** Historical only — an earlier spelling of {@link #MODEL_ASSIGNMENT}. */
    AGENT_BINDING,
    /** Historical only — an earlier spelling of {@link #MODEL_ASSIGNMENT}. */
    AGENT_CONFIG,
    /** Historical only — an earlier spelling of {@link #MODEL_ASSIGNMENT}. */
    AI_CONFIG_BINDING,
    /**
     * A contributor's role or roster visibility: admin-initiated changes, plus role changes org
     * sync applies (actor {@code SYSTEM}). Memberships org sync itself creates or removes are
     * excluded as roster churn that would bury the admin-initiated rows.
     */
    WORKSPACE_ROLE,
    ACTIVITY_AUTOMATION,
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
    CATALOG_PRACTICE,
    CATALOG_GROUP,

    /** A workspace's monthly cap on HOST-funded LLM spend. Set by instance admins. */
    WORKSPACE_INSTANCE_LLM_BUDGET,
    /** A workspace's own cap on spend through its own connected provider. Set by its own admins. */
    WORKSPACE_OWN_PROVIDER_LLM_BUDGET,
    /** Historical only — the earlier spelling of {@link #WORKSPACE_INSTANCE_LLM_BUDGET}. */
    WORKSPACE_LLM_BUDGET,
    /** Historical only — the earlier spelling of {@link #WORKSPACE_OWN_PROVIDER_LLM_BUDGET}. */
    WORKSPACE_BYO_LLM_BUDGET,

    /**
     * A review batch, which can commit a month's LLM budget in a single request; who authorised it
     * must be answerable from the record.
     */
    REVIEW_BATCH,

    /**
     * A review schedule: a standing instruction to review recent work on a cadence — authorised
     * once, spends every night.
     */
    REVIEW_SCHEDULE,

    /**
     * A workspace admin's request to read back on-work feedback whose delivery outcome stayed unknown: who asked, and
     * when. The readback never creates a provider copy.
     */
    FEEDBACK_DELIVERY_RECHECK,

    WORKSPACE_LLM_CONNECTION,
    /** A model on a workspace's own BYO connection, including its inline price and enablement. */
    WORKSPACE_LLM_MODEL,
}
