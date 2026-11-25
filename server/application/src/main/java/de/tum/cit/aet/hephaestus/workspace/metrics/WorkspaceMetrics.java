package de.tum.cit.aet.hephaestus.workspace.metrics;

public final class WorkspaceMetrics {

    public static final String WORKSPACE_SYNC_PAUSED = "workspace.sync.paused";

    /** Monitored repositories by visibility class, proof state and proof reason. */
    public static final String MONITORED_REPOSITORY_READERS = "monitored.repository.readers";

    /**
     * How long the monitored repository whose proof has waited longest has waited: for a listing or a public answer
     * that is still pending, or for the renewal of a proven one.
     */
    public static final String MONITORED_REPOSITORY_READERS_WAITING = "monitored.repository.readers.waiting.seconds";

    private WorkspaceMetrics() {}
}
