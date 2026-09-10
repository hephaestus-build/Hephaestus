package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceAccessEntryService {
    private final WorkspaceRepository workspaces;
    private final WorkspaceAccessPolicyRepository policies;
    private final WorkspaceAccountMembershipRepository memberships;
    private final WorkspaceAccessCatalog catalog;
    private final AccountContactQuery contacts;

    @Transactional(readOnly = true)
    public WorkspaceAccessEntryDTO entry(Long workspaceId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        var policy = policies.findByWorkspace_Id(workspaceId)
                .filter(WorkspaceAccessPolicy::isEnabled)
                .orElse(null);
        if (policy == null) return new WorkspaceAccessEntryDTO(workspace.getDisplayName(), false, null);
        var provider = catalog.primary(workspace, policy.getSettings());
        return new WorkspaceAccessEntryDTO(
                workspace.getDisplayName(),
                true,
                new WorkspaceAccessProviderDTO(
                        provider.registrationId(), provider.displayName(), provider.type(), provider.serverUrl()));
    }

    @Transactional(readOnly = true)
    public WorkspaceAccessFormDTO form(Long workspaceId) {
        Long accountId = SecurityUtils.getCurrentAccountId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        var policy = policies.findByWorkspace_Id(workspaceId)
                .filter(WorkspaceAccessPolicy::isEnabled)
                .orElseThrow(() -> WorkspaceAccessCatalog.conflict("This workspace is not accepting access requests"));
        catalog.primaryLink(workspace, policy.getSettings(), accountId);
        var settings = policy.getSettings();
        var membership = memberships
                .findByWorkspace_IdAndAccountId(workspaceId, accountId)
                .orElse(null);
        // The mailbox and retention configuration belong to administrators, never to an applicant's form.
        return new WorkspaceAccessFormDTO(
                policy.getVersion(),
                settings.introductionMarkdown(),
                settings.acknowledgementLabel(),
                settings.notices(),
                catalog.linkOptions(settings, accountId),
                catalog.teamOptions(workspace, settings),
                catalog.maintainers(workspace, settings).stream()
                        .filter(value -> !value.accountId().equals(accountId))
                        .toList(),
                settings.maximumDurationDays(),
                contacts.verifiedEmail(accountId).isPresent(),
                membership != null && membership.isActive());
    }

    @Transactional(readOnly = true)
    public WorkspaceAccessReviewOptionsDTO reviewOptions(Long workspaceId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        var policy = policies.findByWorkspace_Id(workspaceId).orElse(null);
        if (policy == null) return new WorkspaceAccessReviewOptionsDTO(List.of(), List.of(), 0);
        return new WorkspaceAccessReviewOptionsDTO(
                catalog.teamOptions(workspace, policy.getSettings()),
                catalog.maintainers(workspace, policy.getSettings()),
                policy.getSettings().maximumDurationDays());
    }

    record WorkspaceAccessReviewOptionsDTO(
            @NonNull List<WorkspaceAccessCatalog.WorkspaceAccessTeamOptionDTO> requestableTeams,
            @NonNull List<WorkspaceAccessCatalog.WorkspaceAccessMaintainerOptionDTO> maintainers,
            int maximumDurationDays) {}

    record WorkspaceAccessEntryDTO(
            @NonNull String workspaceName,
            boolean acceptingRequests,
            @Nullable WorkspaceAccessProviderDTO primaryProvider) {}

    record WorkspaceAccessProviderDTO(
            @NonNull String registrationId,
            @NonNull String displayName,
            @NonNull String providerType,
            @NonNull String serverUrl) {}

    record WorkspaceAccessFormDTO(
            long policyVersion,
            @NonNull String introductionMarkdown,
            @NonNull String acknowledgementLabel,
            @NonNull List<WorkspaceAccessPolicySettings.PolicyNoticeDTO> notices,
            @NonNull List<WorkspaceAccessCatalog.WorkspaceAccessLinkOptionDTO> requiredLinks,
            @NonNull List<WorkspaceAccessCatalog.WorkspaceAccessTeamOptionDTO> requestableTeams,
            @NonNull List<WorkspaceAccessCatalog.WorkspaceAccessMaintainerOptionDTO> maintainers,
            int maximumDurationDays,
            boolean verifiedContactAvailable,
            boolean accessActive) {}
}
