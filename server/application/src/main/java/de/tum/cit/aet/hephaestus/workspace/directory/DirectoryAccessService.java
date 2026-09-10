package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.DirectoryIdentitySourceQuery;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account-scoped discovery: no caller-supplied identity, email, username or directory subject. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class DirectoryAccessService {
    private final DirectoryPolicyRepository policies;
    private final DirectoryPolicyService policyService;
    private final DirectoryIdentitySourceQuery sources;
    private final AccountIdentityQuery identities;
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccountMembershipRepository memberships;
    private final DirectoryMembershipAdapter managedAccess;
    private final ConnectionRepository connections;

    public record WorkspaceAccessOfferDTO(
            @NonNull Long workspaceId,
            @NonNull String slug,
            @NonNull String displayName,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean joined,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean suspended) {}

    @Transactional(readOnly = true)
    public List<WorkspaceAccessOfferDTO> offers() {
        long accountId = currentAccount();
        if (identities
                .account(accountId)
                .filter(AccountIdentityQuery.AccountView::active)
                .isEmpty()) return List.of();
        var links = identities.activeLinksForAccount(accountId);
        List<WorkspaceAccessOfferDTO> result = new ArrayList<>();
        for (var source : sources.approvedSources()) {
            for (var link : links) {
                if (link.gitProviderId() != source.providerId()) continue;
                for (var policy : policies.findEligibleForSubject(source.registrationId(), link.subject())) {
                    if (policy.getWorkspace().getStatus() == Workspace.WorkspaceStatus.ACTIVE
                            && connected(policy)
                            && policyService.usable(
                                    policy, source, policy.getActiveSnapshot(), policy.getApprovedGroupIds()))
                        result.add(offer(policy.getWorkspace(), accountId));
                }
            }
        }
        return result.stream()
                .distinct()
                .sorted(java.util.Comparator.comparing(WorkspaceAccessOfferDTO::displayName))
                .toList();
    }

    @Transactional
    public WorkspaceAccessOfferDTO join(long workspaceId) {
        long accountId = currentAccount();
        Workspace workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow(() -> unavailable(workspaceId));
        DirectoryPolicy policy = policies.findByWorkspace_Id(workspaceId).orElseThrow(() -> unavailable(workspaceId));
        var source =
                sources.approvedSourceForUpdate(policy.getRegistrationId()).orElseThrow(() -> unavailable(workspaceId));
        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE
                || policy.getStatus() != DirectoryPolicy.Status.ACTIVE
                || !connected(policy)
                || !policyService.usable(policy, source, policy.getActiveSnapshot(), policy.getApprovedGroupIds()))
            throw unavailable(workspaceId);
        identities
                .accountForUpdate(accountId)
                .filter(AccountIdentityQuery.AccountView::active)
                .orElseThrow(() -> unavailable(workspaceId));
        String subject = policyService
                .eligibleSubject(accountId, source, Objects.requireNonNull(policy.getActiveSnapshot()))
                .orElseThrow(() -> unavailable(workspaceId));
        if (memberships
                .findByWorkspace_IdAndAccountId(workspaceId, accountId)
                .filter(member -> member.isSuspended())
                .isPresent())
            throw new AccessForbiddenException(
                    "Your workspace access is suspended. Ask the workspace owner to restore it explicitly.");
        managedAccess.grant(workspace, source.providerId(), accountId, subject);
        return offer(workspace, accountId);
    }

    private WorkspaceAccessOfferDTO offer(Workspace workspace, long accountId) {
        var membership = memberships.findByWorkspace_IdAndAccountId(workspace.getId(), accountId);
        return new WorkspaceAccessOfferDTO(
                workspace.getId(),
                workspace.getWorkspaceSlug(),
                workspace.getDisplayName(),
                membership.filter(member -> !member.isSuspended()).isPresent(),
                membership.map(member -> member.isSuspended()).orElse(false));
    }

    private boolean connected(DirectoryPolicy policy) {
        return connections
                .findByIdAndWorkspaceId(
                        policy.getConnectionId(), policy.getWorkspace().getId())
                .filter(connection -> connection.getState() == IntegrationState.ACTIVE)
                .isPresent();
    }

    private static long currentAccount() {
        return SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new AccessForbiddenException("Sign in to see your workspace access requirements"));
    }

    private static EntityNotFoundException unavailable(long workspaceId) {
        return new EntityNotFoundException("WorkspaceAccessOffer", workspaceId);
    }
}
