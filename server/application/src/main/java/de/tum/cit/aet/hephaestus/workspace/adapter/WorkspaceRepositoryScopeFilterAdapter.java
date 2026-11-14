package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import org.springframework.stereotype.Component;

/**
 * Adapter that bridges the workspace's scope filter to the integration.scm SPI.
 * <p>
 * This allows the integration.scm module (specifically {@code integration.scm.github.common.ProcessingContextFactory})
 * to apply repository filtering without directly depending on workspace internals.
 */
@Component
public class WorkspaceRepositoryScopeFilterAdapter implements RepositoryScopeFilter {

    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final RepositoryToMonitorRepository monitors;

    public WorkspaceRepositoryScopeFilterAdapter(
            WorkspaceScopeFilter workspaceScopeFilter, RepositoryToMonitorRepository monitors) {
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.monitors = monitors;
    }

    @Override
    public boolean isRepositoryAllowed(String nameWithOwner) {
        return workspaceScopeFilter.isGitHubRepositoryAllowed(nameWithOwner);
    }

    @Override
    public boolean isRepositoryMonitored(Long scopeId, Long repositoryId) {
        return !monitors.findByWorkspaceIdAndSourceRepositoryId(scopeId, repositoryId)
                .isEmpty();
    }

    @Override
    public boolean isActive() {
        return workspaceScopeFilter.isActive();
    }
}
