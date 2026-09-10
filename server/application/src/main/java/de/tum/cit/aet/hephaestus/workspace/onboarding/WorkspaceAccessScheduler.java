package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import java.time.Clock;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@Slf4j
class WorkspaceAccessScheduler {
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccountMembershipRepository memberships;
    private final WorkspaceAccessNotificationRepository outbox;
    private final WorkspaceAccessLifecycle lifecycle;
    private final WorkspaceAccessNotifications notifications;
    private final Clock clock;
    private final WorkspaceAccessRequestRepository requests;
    private final WorkspaceAccessRetention retention;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @SchedulerLock(name = "workspace-access-lifecycle", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void advance() {
        for (var workspaceId : memberships.findWorkspaceIdsBySource(WorkspaceAccountMembership.Source.REQUEST)) {
            inWorkspace(workspaceId, () -> lifecycle.advance(workspaceId));
        }
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 60_000)
    @SchedulerLock(name = "workspace-access-email", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    public void deliver() {
        for (var due :
                outbox.findDue(WorkspaceAccessNotification.State.PENDING, clock.instant(), PageRequest.of(0, 25))) {
            inWorkspace(due.getWorkspaceId(), () -> notifications.deliver(due.getWorkspaceId(), due.getId()));
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    @SchedulerLock(name = "workspace-access-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void eraseRetainedSubmissions() {
        for (var workspaceId : requests.findWorkspaceIdsForRetention()) {
            inWorkspace(workspaceId, () -> retention.eraseDue(workspaceId));
        }
    }

    private void inWorkspace(Long workspaceId, Runnable action) {
        var workspace = workspaces.findById(workspaceId).orElse(null);
        if (workspace == null) return;
        var previous = WorkspaceContextHolder.getContext();
        try {
            WorkspaceContextHolder.setContext(WorkspaceContext.fromWorkspace(workspace, Set.of(), null));
            action.run();
        } catch (RuntimeException failure) {
            // One workspace cannot starve the rest; no provider payload or contact details enter logs.
            log.warn(
                    "Workspace access operation failed: workspaceId={}, failureType={}",
                    workspaceId,
                    failure.getClass().getSimpleName());
        } finally {
            WorkspaceContextHolder.clearContext();
            if (previous != null) WorkspaceContextHolder.setContext(previous);
        }
    }
}
