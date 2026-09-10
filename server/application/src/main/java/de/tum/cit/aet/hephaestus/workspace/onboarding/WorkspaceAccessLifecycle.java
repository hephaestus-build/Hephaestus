package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceAccessLifecycle {
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccountMembershipRepository memberships;
    private final WorkspaceAccessRequestRepository requests;
    private final WorkspaceAccessNotifications notifications;
    private final Clock clock;

    @Transactional
    public void advance(Long workspaceId) {
        workspaces.findByIdForUpdate(workspaceId).orElseThrow();
        var now = clock.instant();
        for (var membership : memberships.findByWorkspace_Id(workspaceId)) {
            if (membership.getSource() != WorkspaceAccountMembership.Source.REQUEST) continue;
            var requestId = membership.getAccessRequestId();
            var expiresAt = membership.getExpiresAt();
            if (requestId == null || expiresAt == null) continue;
            var request =
                    requests.findByIdAndWorkspace_Id(requestId, workspaceId).orElse(null);
            if (request == null) continue;
            if (!membership.isActiveAt(now)) {
                notifications.enqueue(request, WorkspaceAccessNotification.Kind.EXPIRED);
            } else if (!now.isBefore(
                    expiresAt.minus(Duration.ofDays(request.getPolicySnapshot().reminderDays())))) {
                notifications.enqueue(request, WorkspaceAccessNotification.Kind.REMINDER);
            }
        }
    }
}
