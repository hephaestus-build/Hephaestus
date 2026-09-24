package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

/**
 * One mirror per workspace and repository: two workspaces that monitor the same upstream fetch it
 * with their own credentials into their own mirrors.
 */
public record RepositoryKey(long workspaceId, long repositoryId) {
    public RepositoryKey {
        if (workspaceId <= 0 || repositoryId <= 0) throw new IllegalArgumentException("Invalid repository key");
    }
}
