package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScopeResolver;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceApprovedAccessQuery;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Only current, approved requests with their original verified identities can drive provider writes. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessRequestEvidence {
    private final WorkspaceApprovedAccessQuery approvals;
    private final AccountIdentityQuery identities;
    private final GitProviderRegistry providers;
    private final WorkspaceTeamScopeResolver scopes;
    private final TeamRepository teams;
    private final Clock clock;

    public void configure(GitHubAccessTarget target, Set<String> groups) {
        githubProvider(target);
        if (!groups.isEmpty())
            throw new IllegalArgumentException(
                    "Access requests use the approved request's teams, not directory groups");
        target.setDirectoryProviderId(null);
        target.setRegistrationId(null);
        target.setIssuer(null);
    }

    public void requireOrganization(GitHubAccessTarget target, long organizationId) {
        githubProvider(target);
        var organization = target.getWorkspace().getOrganization();
        if (organization == null || organization.getNativeId() != organizationId)
            throw new IllegalArgumentException("Request delivery must use this workspace's own GitHub organization");
    }

    public void bindScope(GitHubAccessTarget target, long organizationId, long scopeId) {
        requireOrganization(target, organizationId);
        if (scopeId == 0) {
            target.setRequestTeamId(null);
            return;
        }
        if (target.isAuthorityHeld() && target.getRequestTeamId() != null) return;
        var scope = scopes.resolve(target.getWorkspace()).orElseThrow();
        var team = teams.findByNativeIdAndProviderId(scopeId, scope.providerId())
                .filter(scope::contains)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Synchronize this workspace's team before authorizing request delivery"));
        target.setRequestTeamId(team.getId());
    }

    public GitHubAccessEvidence.Eligibility read(GitHubAccessTarget target) {
        requireOrganization(target, Objects.requireNonNull(target.getOrganizationId()));
        long providerId = githubProvider(target);
        long targetScope = Objects.requireNonNull(target.getScopeId());
        if (targetScope != 0 && target.getRequestTeamId() == null)
            throw new IllegalArgumentException("Authorize the request's synchronized team before delivering access");
        var now = clock.instant();
        var current = approvals.currentApprovals(target.getWorkspace().getId(), now);
        var profiles = identities.accounts(current.stream()
                .map(WorkspaceApprovedAccessQuery.Approval::accountId)
                .collect(Collectors.toSet()));
        List<GitHubAccessEvidence.Candidate> candidates = new ArrayList<>();
        for (var approval : current) {
            var account = profiles.get(approval.accountId());
            if (account == null || !account.active()) continue;
            if (targetScope != 0 && !approval.teamIds().contains(target.getRequestTeamId())) continue;
            var links = identities.activeLinksForAccount(approval.accountId());
            if (!links.stream()
                    .map(AccountIdentityQuery.IdentityLinkView::identityLinkId)
                    .collect(Collectors.toSet())
                    .containsAll(approval.identityLinkIds())) continue;
            var github = links.stream()
                    .filter(link -> link.gitProviderId() == providerId
                            && approval.identityLinkIds().contains(link.identityLinkId()))
                    .findFirst()
                    .orElse(null);
            if (github == null) continue;
            long nativeId;
            try {
                nativeId = Long.parseLong(github.subject());
            } catch (NumberFormatException invalid) {
                continue;
            }
            if (nativeId <= 0) continue;
            candidates.add(new GitHubAccessEvidence.Candidate(
                    account.id(),
                    account.displayName(),
                    null,
                    null,
                    github.identityLinkId(),
                    nativeId,
                    approval.requestId(),
                    approval.expiresAt()));
        }
        return new GitHubAccessEvidence.Eligibility(
                GitHubAccessTarget.Source.REQUEST,
                target.getConfigurationVersion(),
                now,
                null,
                Set.of(),
                Map.of(),
                candidates,
                Set.of());
    }

    private long githubProvider(GitHubAccessTarget target) {
        var provider = providers
                .findProviderId("GITHUB", "https://github.com")
                .orElseThrow(
                        () -> new IllegalArgumentException("Configure GitHub.com before delivering access requests"));
        if (scopes.resolve(target.getWorkspace())
                .filter(scope -> scope.providerId().equals(provider))
                .isEmpty())
            throw new IllegalArgumentException("GitHub request delivery requires a GitHub.com workspace");
        return provider;
    }
}
