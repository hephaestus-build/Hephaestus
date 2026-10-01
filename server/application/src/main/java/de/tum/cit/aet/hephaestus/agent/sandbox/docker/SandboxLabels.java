package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

/** Docker label keys for managed sandbox containers and the Git preparation containers beside them. */
public final class SandboxLabels {

    public static final String OWNER = "hephaestus.sandbox-owner";
    public static final String JOB_ID = "hephaestus.job-id";
    public static final String KIND = "hephaestus.kind";

    public static final String KIND_ATTEMPT_WORKSPACE = "attempt-workspace";
    public static final String CREATED_AT = "hephaestus.created-at";

    public static final String KIND_SYNC = "sync";
    public static final String KIND_INTERACTIVE = "interactive";

    public static final String SESSION_ID = "hephaestus.session-id";

    /** The application container that created an interactive resource, and the start of that container. */
    public static final String CREATOR_CONTAINER = "hephaestus.creator-container";

    public static final String CREATOR_STARTED_AT = "hephaestus.creator-started-at";

    private SandboxLabels() {}
}
