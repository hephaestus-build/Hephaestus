package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.event.RepositoryExcludedFromWorkspacesEvent;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import java.util.EnumSet;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** Withdraws a newly excluded repository from each workspace independently. */
@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("Finds monitors across workspaces and rechecks each exclusion under the workspace lock")
public class ExcludedRepositoryWithdrawalService {
    private static final Logger log = LoggerFactory.getLogger(ExcludedRepositoryWithdrawalService.class);

    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ConnectionRepository connectionRepository;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final WorkspaceRepositoryMonitorService repositoryMonitors;
    private final TransactionTemplate transactionTemplate;

    /**
     * Takes an excluded repository out of every workspace with what they hold of it. A GitLab project is a different
     * repository that can share the path, so its workspace keeps it.
     */
    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onExcludedFromWorkspaces(RepositoryExcludedFromWorkspacesEvent event) {
        // Each workspace in its own transaction: one that fails must not keep the repository in all the others.
        int failed = 0;
        for (RepositoryToMonitor found :
                repositoryToMonitorRepository.findAllWithWorkspaceByNameIgnoringCase(event.nameWithOwner())) {
            Long workspaceId = Objects.requireNonNull(
                    Objects.requireNonNull(found.getWorkspace()).getId());
            Long monitorId = found.getId();
            try {
                ownTransaction().executeWithoutResult(status -> withdrawFrom(workspaceId, monitorId));
            } catch (RuntimeException failure) {
                failed++;
                log.error(
                        "Could not remove an excluded repository from a workspace: workspaceId={}, syncTargetId={},"
                                + " errorType={}",
                        workspaceId,
                        monitorId,
                        failure.getClass().getSimpleName());
            }
        }
        if (failed > 0) {
            throw new IllegalStateException("An excluded repository stayed in " + failed + " workspace(s)");
        }
    }

    /** A transaction of its own, even when a caller publishes the event inside one. */
    private TransactionTemplate ownTransaction() {
        var own = new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return own;
    }

    private void withdrawFrom(Long workspaceId, Long monitorId) {
        // A live GitLab connection, suspended too: its project on the same path is another repository, and is kept.
        // An uninstalled one is history, and its workspace may monitor GitHub now.
        boolean gitLab = EnumSet.of(IntegrationState.ACTIVE, IntegrationState.SUSPENDED).stream()
                .anyMatch(state -> connectionRepository
                        .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                                workspaceId, IntegrationKind.GITLAB, state)
                        .isPresent());
        if (gitLab) return;
        Workspace workspace = workspaceRepository
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
        // Re-read under the workspace lock, and ask the exclusion again: the destination may have been replaced, or the
        // monitor renamed, since the caller looked.
        repositoryToMonitorRepository
                .findById(monitorId)
                .filter(monitor -> Objects.equals(
                        Objects.requireNonNull(monitor.getWorkspace()).getId(), workspaceId))
                .filter(monitor -> workspaceScopeFilter.isExcluded(monitor.getNameWithOwner()))
                .ifPresent(monitor -> {
                    log.info(
                            "Removed an excluded repository from a workspace: workspaceId={}, syncTargetId={}",
                            workspaceId,
                            monitorId);
                    repositoryMonitors.withdrawRepository(workspace, monitor);
                });
    }
}
