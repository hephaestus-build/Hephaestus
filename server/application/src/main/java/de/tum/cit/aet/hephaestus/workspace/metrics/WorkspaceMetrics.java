package de.tum.cit.aet.hephaestus.workspace.metrics;

public final class WorkspaceMetrics {

    public static final String WORKSPACE_SYNC_PAUSED = "workspace.sync.paused";

    /** Monitored repositories by visibility class, proof state and proof reason. */
    public static final String MONITORED_REPOSITORY_READERS = "monitored.repository.readers";

    /** How long the monitored repository whose readers have waited longest has waited for their proof. */
    public static final String MONITORED_REPOSITORY_READERS_PENDING = "monitored.repository.readers.pending.seconds";

    private WorkspaceMetrics() {}
}
