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

    /**
     * A Git preparation container or mirror volume carries its owner under its own key so that
     * {@link SandboxReconciler}, which lists by {@link #OWNER} and reasons about job ids, never sees
     * it; {@link NativeGitVolumeReconciler} owns that lifecycle.
     */
    public static final String GIT_OWNER = "hephaestus.git-owner";

    public static final String GIT_COMPONENT = "hephaestus.component";
    public static final String GIT_COMPONENT_PREPARATION = "git-preparation";
    public static final String GIT_WORKER = "hephaestus.worker";
    public static final String GIT_DEADLINE = "hephaestus.deadline";
    public static final String GIT_WORKSPACE = "hephaestus.workspace";
    public static final String GIT_REPOSITORY = "hephaestus.repository";

    private SandboxLabels() {}
}
