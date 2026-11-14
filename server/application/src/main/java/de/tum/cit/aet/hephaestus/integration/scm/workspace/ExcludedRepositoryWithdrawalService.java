package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.event.RepositoryExcludedFromWorkspacesEvent;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorQuery;
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

    private final RepositoryMonitorQuery monitors;
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

    /** A transaction of its own, even when a caller publishes the event inside one. */
    private TransactionTemplate ownTransaction() {
        var own = new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return own;
    }
}
