package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAccessRetentionParticipant;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deletes only onboarding submissions and mail, never shared accounts, research or audit records. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceAccessRetention {
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccountMembershipRepository memberships;
    private final WorkspaceAccessRequestRepository requests;
    private final WorkspaceAccessNotificationRepository notifications;
    private final List<WorkspaceAccessRetentionParticipant> participants;
    private final Clock clock;

    @Transactional
    public void eraseDue(Long workspaceId) {
        workspaces.findByIdForUpdate(workspaceId).orElseThrow();
        var now = clock.instant();
        for (var accountId : requests.findAccountIdsInWorkspace(workspaceId)) {
            var membership = memberships.findByWorkspace_IdAndAccountId(workspaceId, accountId);
            if (membership.isPresent() && membership.get().isActiveAt(now)) continue;
            // Renewal chains are one retention unit: no deletion while any submission is still needed.
            var history = requests.findByWorkspace_IdAndAccountIdOrderBySubmittedAtDescIdDesc(
                    workspaceId, accountId, Pageable.unpaged());
            if (history.isEmpty() || !history.stream().allMatch(request -> isDue(request, now))) continue;
            if (!participants.stream()
                    .allMatch(participant -> participant.canEraseAccessRequests(workspaceId, accountId))) continue;
            participants.forEach(participant -> participant.eraseAccessRequestData(workspaceId, accountId));
            memberships.clearAccessRequestForAccount(workspaceId, accountId);
            notifications.deleteAllByWorkspace_IdAndRequest_AccountId(workspaceId, accountId);
            requests.deleteAllByWorkspace_IdAndAccountId(workspaceId, accountId);
        }
    }

    static boolean isDue(WorkspaceAccessRequest request, Instant now) {
        var days = request.getPolicySnapshot().personalDataRetentionDays();
        if (days == null) return false;
        var end =
                switch (request.getStatus()) {
                    case SUBMITTED, CHANGES_REQUESTED -> null;
                    case APPROVED ->
                        request.getApprovedDetails() == null
                                ? null
                                : request.getApprovedDetails().expiresAt();
                    case REJECTED, CANCELLED, SUPERSEDED -> request.getDecidedAt();
                };
        return end != null && !now.isBefore(end.plus(Duration.ofDays(days)));
    }
}
