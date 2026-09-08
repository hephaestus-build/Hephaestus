package de.tum.cit.aet.hephaestus.workspace.access;

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
public class GitHubAccessScheduler {
    private final GitHubAccessTargetRepository policies;
    private final GitHubAccessJobService reconciliation;
    private final java.time.Clock clock;

    @WorkspaceAgnostic(
            "Enumerates independent GitHub target addresses; the sync adapter establishes workspace context for each pass")
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    @SchedulerLock(name = "github-access-reconciliation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        for (var target : policies.scheduled(clock.instant())) {
            try {
                reconciliation.scheduled(target.getWorkspaceId(), target.getTargetId());
            } catch (RuntimeException failure) {
                // Each workspace progresses independently; details belong to its policy and job ledger.
                log.warn(
                        "Could not dispatch GitHub access reconciliation: workspaceId={}, targetId={}, failureType={}",
                        target.getWorkspaceId(),
                        target.getTargetId(),
                        failure.getClass().getSimpleName());
            }
        }
    }
}
