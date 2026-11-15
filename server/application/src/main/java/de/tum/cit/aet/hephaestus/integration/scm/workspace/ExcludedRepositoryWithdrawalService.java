package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.event.RepositoryExcludedFromWorkspacesEvent;
import de.tum.cit.aet.hephaestus.core.event.RepositoryWithdrawnFromWorkspaceEvent;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryHoldings;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorQuery;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** Withdraws a newly excluded repository from each workspace independently. */
@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("Finds holders and monitors across workspaces and rechecks each exclusion under the workspace lock")
public class ExcludedRepositoryWithdrawalService {
    private static final Logger log = LoggerFactory.getLogger(ExcludedRepositoryWithdrawalService.class);

    private final RepositoryMonitorQuery monitors;
    private final RepositoryRepository repositories;
    private final ItemIndexService items;
    // Absent in a role without Activity; that role holds no Activity to withdraw.
    private final List<RepositoryHoldings> holdings;
    private final WorkspaceQueries workspaces;
    private final WorkspaceScopeFilter scopeFilter;
    private final ApplicationEventPublisher eventPublisher;
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
        int failed = withdrawHoldings(event.nameWithOwner());
        for (var found : monitors.findByNameIgnoringCase(event.nameWithOwner())) {
            long workspaceId = found.workspaceId();
            long monitorId = found.monitorId();
            try {
                ownTransaction()
                        .executeWithoutResult(
                                status -> repositoryMonitors.withdrawExcludedRepository(workspaceId, monitorId));
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

    /**
     * Stopping a monitor keeps the workspace's Activity and the work under the removed repository item, so monitors
     * alone do not find every holder. The ids name GitHub mirrors only: a GitLab project on the same path is another
     * repository. This runs before the monitors go, because the last one deletes the mirror row, and Activity then
     * loses the repository id that finds it.
     */
    private int withdrawHoldings(String nameWithOwner) {
        List<Long> repositoryIds = repositories.findGitHubIdsByNameIgnoringCase(nameWithOwner);
        if (repositoryIds.isEmpty()) return 0;
        SortedSet<Long> holders =
                new TreeSet<>(items.workspacesHoldingSources(ScmItemEvents.REPOSITORY.value(), repositoryIds));
        holdings.forEach(holding -> holders.addAll(holding.workspacesHolding(repositoryIds)));
        int failed = 0;
        for (long workspaceId : holders) {
            try {
                ownTransaction().executeWithoutResult(status -> {
                    if (workspaces.findByIdForUpdate(workspaceId).isEmpty()) return;
                    // Asked again under the workspace lock: the destination may have been replaced since.
                    if (!scopeFilter.isExcluded(nameWithOwner)) return;
                    eventPublisher.publishEvent(new RepositoryWithdrawnFromWorkspaceEvent(workspaceId, repositoryIds));
                    log.info("Removed what a workspace kept of an excluded repository: workspaceId={}", workspaceId);
                });
            } catch (RuntimeException failure) {
                failed++;
                log.error(
                        "Could not remove what a workspace kept of an excluded repository: workspaceId={},"
                                + " errorType={}",
                        workspaceId,
                        failure.getClass().getSimpleName());
            }
        }
        return failed;
    }

    /** A transaction of its own, even when a caller publishes the event inside one. */
    private TransactionTemplate ownTransaction() {
        var own = new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return own;
    }
}
