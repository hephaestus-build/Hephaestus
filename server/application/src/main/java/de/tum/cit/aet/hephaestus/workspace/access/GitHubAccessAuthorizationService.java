package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitHubAccessAuthorizationAudit;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verifies a genuine linked owner and atomically consumes their workspace-bound approval capability. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessAuthorizationService {
    private final GitHubAccessTargetRepository targets;
    private final GitHubAccessPolicyService policies;
    private final GitHubAccessEligibilityService evidence;
    private final AccountIdentityQuery identities;
    private final GitProviderRegistry providers;
    private final ConnectionRepository connections;
    private final ConnectionService connectionService;
    private final CredentialBundleConverter converter;
    private final ConfigAuditPort audit;
    private final Clock clock;
    private final GitHubAccessAuthorizationAudit authorizationAudit;

    public record ApprovalInput(
            long workspaceId,
            long targetId,
            long configurationVersion,
            String handoffHash,
            String organization,
            @Nullable String team,
            long installationId,
            long accountId,
            long githubIdentityLinkId,
            long githubUserId,
            @Nullable Long organizationId,
            @Nullable Long scopeId) {}

    /** The capability reveals setup intent only; no installation or private membership lookup occurs here. */
    @Transactional(readOnly = true)
    public ApprovalInput prepareApproval(String token) {
        long accountId = genuineActor();
        var target = targets.findHandoff(GitHubAccessPolicyService.hash(token), clock.instant())
                .orElseThrow(() -> new IllegalArgumentException(
                        "This approval link expired or was already used; ask the workspace owner for a new link"));
        var link = githubLink(accountId);
        return new ApprovalInput(
                target.getWorkspace().getId(),
                target.getId(),
                target.getConfigurationVersion(),
                Objects.requireNonNull(target.getHandoffHash()),
                target.getRequestedOrganization(),
                target.getRequestedTeam(),
                target.getPendingInstallationId(),
                accountId,
                link.identityLinkId(),
                nativeId(link.subject()),
                target.getOrganizationId(),
                target.getScopeId());
    }

    /** SQL authority constraints arbitrate concurrent and overlapping claims, not an earlier inventory check. */
    @Transactional
    public GitHubAccessTarget completeApproval(ApprovalInput input, GitHubAccessClient.Session verified) {
        if (genuineActor() != input.accountId()) throw new IllegalArgumentException("The authorizing account changed");
        policies.lockActive(input.workspaceId());
        if (identities
                .accountForUpdate(input.accountId())
                .filter(AccountIdentityQuery.AccountView::active)
                .isEmpty())
            throw new IllegalArgumentException("Sign in with an active account before approving GitHub access");
        var link = githubLink(input.accountId());
        var target = policies.required(input.workspaceId(), input.targetId());
        if (target.getConfigurationVersion() != input.configurationVersion()
                || !input.handoffHash().equals(target.getHandoffHash())
                || target.getHandoffExpiresAt() == null
                || !target.getHandoffExpiresAt().isAfter(clock.instant())
                || link.identityLinkId() != input.githubIdentityLinkId()
                || nativeId(link.subject()) != input.githubUserId()
                || target.getStatus() == GitHubAccessTarget.Status.ENDED
                || !policies.isCurrentOwner(input.workspaceId(), target.getHandoffIssuedByAccountId())
                || verified.installationId() != input.installationId()
                || (!target.isAuthorityHeld() && !verified.organizationLogin().equalsIgnoreCase(input.organization()))
                || (target.isAuthorityHeld()
                        && (!Objects.equals(target.getOrganizationId(), verified.organizationId())
                                || !Objects.equals(target.getScopeId(), verified.scopeId()))))
            throw new IllegalArgumentException("The approval or target changed; obtain a fresh approval link");
        evidence.bindScope(target, verified.organizationId(), verified.scopeId());
        var before = GitHubAccessAudit.Policy.of(target);
        target.setOrganizationId(verified.organizationId());
        target.setScopeId(verified.scopeId());
        target.setOrganizationLogin(verified.organizationLogin());
        target.setScopeName(verified.scopeName());
        target.setAuthorityHeld(true);
        target.setAuthorization(new GitHubAccessEvidence.Authorization(
                target.getConfigurationVersion(),
                input.accountId(),
                input.githubIdentityLinkId(),
                input.githubUserId(),
                verified.installationId(),
                verified.permissions(),
                clock.instant()));
        target.setHandoffHash(null);
        target.setHandoffExpiresAt(null);
        target.setHandoffIssuedByAccountId(null);
        target.setPreview(null);
        target.setFailureCode(null);
        target.setFailureReason(null);
        target.setRetryAt(null);
        var connection = connections
                .findByIdAndWorkspaceId(target.getConnectionId(), input.workspaceId())
                .orElseThrow();
        connection.setCredentials(
                new InstallationCredential(verified.installationId(), "https://github.com"), converter);
        connectionService.transition(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "AUTHORIZE_ACCESS",
                        "USER",
                        Long.toString(input.accountId()),
                        UUID.randomUUID().toString(),
                        "GitHub organization owner authorized the workspace membership scope"));
        targets.flush();
        record(target, before);
        authorizationAudit.authorized(input.accountId(), input.workspaceId(), input.githubIdentityLinkId());
        return target;
    }

    private AccountIdentityQuery.IdentityLinkView githubLink(long accountId) {
        Long providerId = providers
                .findProviderId("GITHUB", "https://github.com")
                .orElseThrow(
                        () -> new IllegalArgumentException("Link your GitHub.com account before approving access"));
        return identities.activeLinksForAccount(accountId).stream()
                .filter(link -> link.gitProviderId().equals(providerId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Link your GitHub.com account before approving access"));
    }

    private static long genuineActor() {
        long accountId = CurrentAccount.requireId();
        if (CurrentAccount.impersonatorId() != null)
            throw new IllegalArgumentException("Exit impersonation and sign in as yourself to authorize GitHub access");
        return accountId;
    }

    private static long nativeId(String subject) {
        try {
            long id = Long.parseLong(subject);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // A verified link with an unexpected provider subject cannot authorize a native user.
        }
        throw new IllegalArgumentException("The linked GitHub identity has no valid immutable user ID");
    }

    private void record(GitHubAccessTarget target, GitHubAccessAudit.Policy before) {
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.GITHUB_ACCESS_POLICY,
                target.getId(),
                target.getWorkspace().getId(),
                before,
                GitHubAccessAudit.Policy.of(target)));
    }
}
