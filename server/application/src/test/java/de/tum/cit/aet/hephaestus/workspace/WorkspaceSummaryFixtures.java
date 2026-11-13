package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;

/** Runs metadata fixtures through the workspace's existing record mapper. */
public final class WorkspaceSummaryFixtures {
    private WorkspaceSummaryFixtures() {}

    public static WorkspaceSummaryQuery summaries(WorkspaceRepository repository) {
        return new WorkspaceSummaryQueryAdapter(repository);
    }
}
