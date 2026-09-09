package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Removes submitted personal data as part of the existing transactional purge operations. */
@Component
@RequiredArgsConstructor
class WorkspaceAccessErasure implements AccountErasureContributor, WorkspacePurgeContributor {
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccountMembershipRepository memberships;
    private final WorkspaceAccessNotificationRepository notifications;
    private final WorkspaceAccessRequestRepository requests;
    private final WorkspaceAccessPolicyRepository policies;

    @Override
    public void eraseAccount(long accountId) {
        for (Long workspaceId : requests.findWorkspaceIdsForErasure(accountId)) {
            // Purge already owns the account lock; do not wait behind admission's workspace-first lock.
            workspaces.findByIdForUpdateNowait(workspaceId).orElseThrow();
            memberships.clearAccessRequestForAccount(workspaceId, accountId);
            notifications.deleteAllByWorkspace_IdAndRequest_AccountId(workspaceId, accountId);
            requests.deleteAllByWorkspace_IdAndAccountId(workspaceId, accountId);
            requests.eraseReviewerReference(workspaceId, accountId);
        }
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        memberships.clearAccessRequests(workspaceId);
        notifications.deleteAllByWorkspace_Id(workspaceId);
        requests.deleteAllByWorkspace_Id(workspaceId);
        policies.deleteAllByWorkspace_Id(workspaceId);
    }
}
