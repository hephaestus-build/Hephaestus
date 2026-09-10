package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDeletionGuard;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.IdentityUnlinkParticipant;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure;
import de.tum.cit.aet.hephaestus.workspace.spi.DirectorySubjectRetention;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAccessRetentionParticipant;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeGuard;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Local lifecycle changes retain security obligations; only provider read-back can finish removal. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class GitHubAccessLifecycle
        implements AccountDeletionGuard,
                IdentityUnlinkParticipant,
                AccountErasureContributor,
                WorkspacePurgeGuard,
                WorkspacePurgeContributor,
                DirectorySubjectRetention,
                WorkspaceAccessRetentionParticipant {
    private final GitHubAccessTargetRepository targets;
    private final GitHubAccessMembershipRepository memberships;
    private final GitHubAccessActionRepository actions;
    private final GitProviderRegistry providers;
    private final ConfigAuditPort audit;

    @Override
    public boolean canEraseAccessRequests(Long workspaceId, Long accountId) {
        return memberships.findByWorkspace_IdAndAccountId(workspaceId, accountId).stream()
                .noneMatch(this::hasObligation);
    }

    @Override
    public void eraseAccessRequestData(Long workspaceId, Long accountId) {
        if (!canEraseAccessRequests(workspaceId, accountId))
            throw new IllegalStateException("Confirm external removals before erasing their access history");
        for (var member : memberships.findByWorkspace_IdAndAccountId(workspaceId, accountId)) {
            actions.deleteAllByWorkspace_IdAndMembership_Id(workspaceId, member.getId());
            memberships.delete(member);
        }
        targets.erasePreviewIdentityInWorkspace(workspaceId, accountId);
    }

    @Override
    public List<String> retainedSubjects(long workspaceId, long providerId) {
        return memberships.trackedDirectorySubjects(workspaceId, providerId);
    }

    @Override
    public void beforeDeletion(Long accountId) {
        // Account deletion/unlink already holds the account lock. Taking a workspace lock here
        // would invert the reconciler's workspace -> accounts -> target order.
        memberships.findByAccountId(accountId).forEach(this::requestRevocation);
        targets.authorizedByAccount(accountId).forEach(target -> clearAuthorization(target, accountId));
        targets.erasePreviewIdentity(accountId);
    }

    @Override
    public void beforeUnlink(Long accountId, Long providerId, String subject) {
        boolean github = providers
                .findProviderId("GITHUB", "https://github.com")
                .filter(providerId::equals)
                .isPresent();
        for (var member : memberships.findByAccountId(accountId)) {
            boolean exactGithub =
                    github && Long.toString(member.getGithubUserId()).equals(subject);
            boolean exactDirectory = providerId.equals(member.getTarget().getDirectoryProviderId())
                    && subject.equals(member.getDirectorySubject());
            if (exactGithub || exactDirectory) requestRevocation(member);
        }
        if (github) {
            for (var target : targets.authorizedByAccount(accountId)) {
                var authorization = target.getAuthorization();
                if (authorization != null
                        && Long.toString(authorization.githubUserId()).equals(subject))
                    clearAuthorization(target, accountId);
            }
        }
        targets.erasePreviewIdentity(accountId);
    }

    @Override
    public void eraseAccount(long accountId) {
        beforeDeletion(accountId);
        for (var member : memberships.findByAccountId(accountId)) {
            if (hasObligation(member)) {
                member.setAccountId(null);
                member.setGithubIdentityLinkId(null);
                member.setDirectoryIdentityLinkId(null);
                member.setDirectorySubject(null);
                member.setGithubLogin(null);
                member.setExceptionReason(null);
                member.setBlocker(
                        "Account erased; confirm removal of the retained GitHub identity before clearing this obligation");
            } else {
                actions.deleteAllByWorkspace_IdAndMembership_Id(
                        member.getWorkspace().getId(), member.getId());
                memberships.delete(member);
            }
        }
        for (var target : targets.authorizedByAccount(accountId)) {
            if (Long.valueOf(accountId).equals(target.getHandoffIssuedByAccountId())) {
                target.setHandoffHash(null);
                target.setHandoffExpiresAt(null);
                target.setHandoffIssuedByAccountId(null);
            }
            if (Long.valueOf(accountId).equals(target.getApprovedByAccountId())) {
                target.setApprovedByAccountId(null);
                target.setApprovedAt(null);
                target.setPreview(null);
            }
        }
    }

    @Override
    public void verifyQuiescent(Long workspaceId) {
        for (var target : targets.findByWorkspace_IdOrderById(workspaceId)) {
            if (target.isAuthorityHeld()
                    || target.getStatus() == GitHubAccessTarget.Status.ACTIVE
                    || target.getStatus() == GitHubAccessTarget.Status.ENDING
                    || memberships.findByWorkspace_IdAndTarget_IdOrderById(workspaceId, target.getId()).stream()
                            .anyMatch(this::hasObligation)) {
                throw new WorkspacePurgeBlockedException(
                        "End each GitHub access target and confirm all managed removals before deleting this workspace. "
                                + "Paused or unavailable GitHub access has not been revoked; restore credentials or resolve the recorded manual-recovery obligations.");
            }
        }
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        verifyQuiescent(workspaceId);
        actions.deleteAllByWorkspace_Id(workspaceId);
        memberships.deleteAllByWorkspace_Id(workspaceId);
        targets.deleteAllByWorkspace_Id(workspaceId);
    }

    @Override
    public int getOrder() {
        return -310;
    }

    private boolean hasObligation(GitHubAccessMembership member) {
        return member.isManaged()
                || member.isRevocationRequested()
                || actions
                        .findByWorkspace_IdAndTarget_IdAndStatusInOrderById(
                                member.getWorkspace().getId(),
                                member.getTarget().getId(),
                                Set.of(GitHubAccessAction.Status.PENDING, GitHubAccessAction.Status.MANUAL_RECOVERY))
                        .stream()
                        .anyMatch(action -> action.getMembership().getId().equals(member.getId()));
    }

    private void requestRevocation(GitHubAccessMembership member) {
        if (!hasObligation(member)) return;
        var before = GitHubAccessAudit.Membership.of(member);
        member.setRevocationRequested(true);
        member.setBlocker("Eligibility ended; external access remains until GitHub confirms removal");
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.GITHUB_ACCESS_MEMBERSHIP,
                member.getId(),
                member.getWorkspace().getId(),
                before,
                GitHubAccessAudit.Membership.of(member)));
    }

    private void clearAuthorization(GitHubAccessTarget target, long accountId) {
        var authorization = target.getAuthorization();
        if (authorization == null || authorization.accountId() != accountId) return;
        var before = GitHubAccessAudit.Policy.of(target);
        target.setAuthorization(null);
        target.setPreview(null);
        target.setHandoffHash(null);
        target.setHandoffExpiresAt(null);
        target.setHandoffIssuedByAccountId(null);
        target.setConfigurationVersion(target.getConfigurationVersion() + 1);
        target.setFailureCode(GitHubAccessFailure.Reason.AUTHORITY_LOST);
        target.setFailureReason(
                "The authorizing account or GitHub link was removed. External access remains; obtain fresh organization-owner approval to finish pending work.");
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.GITHUB_ACCESS_POLICY,
                target.getId(),
                target.getWorkspace().getId(),
                before,
                GitHubAccessAudit.Policy.of(target)));
    }
}
