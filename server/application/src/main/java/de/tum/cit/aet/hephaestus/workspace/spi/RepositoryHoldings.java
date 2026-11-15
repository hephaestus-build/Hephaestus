package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.Collection;
import java.util.List;

/**
 * The workspaces that still hold work or Activity of these source repositories, whether they monitor them or not. A
 * stopped monitor keeps its workspace's history, so a repository that must leave every workspace looks here as well.
 */
public interface RepositoryHoldings {
    List<Long> workspacesHolding(Collection<Long> repositoryIds);
}
