package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@Slf4j
public class DirectoryReconciliationScheduler {
    private final DirectoryPolicyRepository policies;
    private final DirectoryReconciliationService reconciliation;

    @WorkspaceAgnostic("Enumerates managed workspace IDs; the directory sync adapter establishes context for each pass")
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    @SchedulerLock(name = "directory-access-reconciliation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        for (Long workspaceId : policies.findManagedWorkspaceIds()) {
            try {
                reconciliation.scheduled(workspaceId);
            } catch (RuntimeException failure) {
                // Each workspace progresses independently; details belong to its policy and job ledger.
                log.warn(
                        "Could not dispatch directory reconciliation: workspaceId={}, failureType={}",
                        workspaceId,
                        failure.getClass().getSimpleName());
            }
        }
    }
}
