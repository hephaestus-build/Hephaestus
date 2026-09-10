package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

/** Docker label keys for managed sandbox containers. */
public final class SandboxLabels {

    public static final String OWNER = "hephaestus.sandbox-owner";
    public static final String JOB_ID = "hephaestus.job-id";
    public static final String KIND = "hephaestus.kind";

    public static final String KIND_SYNC = "sync";
    public static final String KIND_INTERACTIVE = "interactive";

    public static final String SESSION_ID = "hephaestus.session-id";

    private SandboxLabels() {}
}
