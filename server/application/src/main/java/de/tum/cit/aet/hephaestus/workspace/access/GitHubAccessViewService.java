package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import de.tum.cit.aet.hephaestus.workspace.access.GitHubAccessTargetDTO.GitHubAccessActionDTO;
import de.tum.cit.aet.hephaestus.workspace.access.GitHubAccessTargetDTO.GitHubAccessInventoryMemberDTO;
import de.tum.cit.aet.hephaestus.workspace.access.GitHubAccessTargetDTO.GitHubAccessMemberDTO;
import de.tum.cit.aet.hephaestus.workspace.access.GitHubAccessTargetDTO.GitHubAccessPreviewDTO;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessViewService {
    private final GitHubAccessPolicyService policies;
    private final GitHubAccessMembershipRepository memberships;
    private final GitHubAccessActionRepository actions;
    private final AccountIdentityQuery identities;
    private final GitHubAccessTargetRepository targets;

    @Transactional(readOnly = true)
    public List<GitHubAccessTargetDTO> list(long workspaceId) {
        return policies.inspect(workspaceId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public GitHubAccessTargetDTO get(long workspaceId, long targetId) {
        policies.requirePermission(workspaceId, false);
        return view(targets.findByIdAndWorkspace_Id(targetId, workspaceId).orElseThrow());
    }

    public record GitHubAccessOfferDTO(
            @NonNull Long workspaceId,
            @NonNull String workspaceSlug,
            @NonNull String workspaceName,
            @NonNull Long targetId,
            @NonNull String organization,
            @Nullable String scopeName,
            @NonNull Boolean enrolled,
            @NonNull Boolean managed,
            @NonNull Boolean paused,
            @NonNull Boolean revocationRequested,
            GitHubAccessClient.@Nullable State externalState,
            @Nullable Instant confirmedAt,
            @Nullable String blocker,
            @NonNull String invitationUrl) {}

    @Transactional(readOnly = true)
    public List<GitHubAccessOfferDTO> offers() {
        return memberships.findByAccountId(CurrentAccount.requireId()).stream()
                .filter(member -> member.getTarget().getStatus() != GitHubAccessTarget.Status.ENDED)
                .map(member -> {
                    var target = member.getTarget();
                    var workspace = member.getWorkspace();
                    String organization = Objects.requireNonNullElse(
                            target.getOrganizationLogin(), target.getRequestedOrganization());
                    return new GitHubAccessOfferDTO(
                            workspace.getId(),
                            workspace.getWorkspaceSlug(),
                            workspace.getDisplayName(),
                            target.getId(),
                            organization,
                            target.getScopeName(),
                            member.isEnrolled(),
                            member.isManaged(),
                            target.isPaused(),
                            member.isRevocationRequested(),
                            member.getExternalState(),
                            member.getConfirmedAt(),
                            member.getBlocker(),
                            "https://github.com/orgs/" + organization + "/invitation");
                })
                .toList();
    }

    public record GitHubAccessHandoffPreviewDTO(
            @NonNull String workspaceName,
            @NonNull String organization,
            @Nullable String team,
            @NonNull Long installationId,
            @NonNull Set<String> groupIds) {}

    @Transactional(readOnly = true)
    public GitHubAccessHandoffPreviewDTO approval(GitHubAccessAuthorizationService.ApprovalInput input) {
        var target = targets.findByIdAndWorkspace_Id(input.targetId(), input.workspaceId())
                .orElseThrow();
        return new GitHubAccessHandoffPreviewDTO(
                target.getWorkspace().getDisplayName(),
                input.organization(),
                input.team(),
                input.installationId(),
                Set.copyOf(target.getDraftGroupIds()));
    }

    private GitHubAccessTargetDTO view(GitHubAccessTarget target) {
        var members = memberships.findByWorkspace_IdAndTarget_IdOrderById(
                target.getWorkspace().getId(), target.getId());
        var accounts = identities.accounts(members.stream()
                .map(GitHubAccessMembership::getAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        var memberViews = members.stream()
                .map(member -> {
                    var account = member.getAccountId() == null ? null : accounts.get(member.getAccountId());
                    return new GitHubAccessMemberDTO(
                            member.getGithubUserId(),
                            member.getAccountId(),
                            account == null ? "Unlinked or erased account" : account.displayName(),
                            member.getGithubLogin(),
                            member.isEnrolled(),
                            member.isManaged(),
                            member.isManualException(),
                            member.getExceptionReason(),
                            member.isRevocationRequested(),
                            member.getExternalState(),
                            member.getConfirmedAt(),
                            member.getBlocker());
                })
                .toList();
        var history = actions
                .findTop50ByWorkspace_IdAndTarget_IdOrderByCreatedAtDesc(
                        target.getWorkspace().getId(), target.getId())
                .stream()
                .map(action -> new GitHubAccessActionDTO(
                        action.getId(),
                        action.getGithubUserId(),
                        action.getType(),
                        action.getStatus(),
                        action.getConfigurationVersion(),
                        action.getCreatedAt(),
                        action.getConfirmedAt(),
                        action.getFailureReason(),
                        action.getRetryAt()))
                .toList();
        var preview = target.getPreview();
        GitHubAccessPreviewDTO previewView = preview == null
                ? null
                : new GitHubAccessPreviewDTO(
                        preview.github().capturedAt(),
                        preview.directory().candidates().size(),
                        (int) preview.directory().candidates().stream()
                                .filter(candidate -> candidate.githubUserId() == null)
                                .count(),
                        preview.github().unlinkedInvitations(),
                        preview.github().members().stream()
                                .map(member -> new GitHubAccessInventoryMemberDTO(
                                        member.userId(), member.login(), member.state(), member.explanation()))
                                .toList());
        var authorization = target.getAuthorization();
        return new GitHubAccessTargetDTO(
                target.getId(),
                target.getConnectionId(),
                target.getRequestedOrganization(),
                target.getRequestedTeam(),
                target.getPendingInstallationId(),
                target.getOrganizationId(),
                target.getScopeId(),
                target.getStatus(),
                target.isPaused(),
                target.isAuthorityHeld(),
                target.getConfigurationVersion(),
                target.getDraftGroupIds(),
                target.getApprovedGroupIds(),
                authorization != null && authorization.configurationVersion() == target.getConfigurationVersion(),
                target.getApprovedAt(),
                target.getLastAttemptAt(),
                target.getLastConfirmedAt(),
                target.getFailureCode(),
                target.getFailureReason(),
                target.getRetryAt(),
                previewView,
                memberViews,
                history);
    }
}
