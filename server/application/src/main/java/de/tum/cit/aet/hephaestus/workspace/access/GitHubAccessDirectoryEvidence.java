package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.DirectoryIdentitySourceQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.directory.DirectoryPolicy;
import de.tum.cit.aet.hephaestus.workspace.directory.DirectoryPolicyRepository;
import de.tum.cit.aet.hephaestus.workspace.directory.DirectoryPolicyService;
import de.tum.cit.aet.hephaestus.workspace.directory.DirectorySnapshot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reuses the approved, read-only directory capture. Missing or stale evidence never means departure. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessDirectoryEvidence {
    private final DirectoryPolicyRepository policies;
    private final DirectoryPolicyService policyService;
    private final DirectoryIdentitySourceQuery sources;
    private final ConnectionRepository connections;
    private final AccountIdentityQuery identities;
    private final GitProviderRegistry providers;
    private final WorkspaceAccountMembershipRepository memberships;
    private final Clock clock;

    public void configure(GitHubAccessTarget target, Set<String> groups) {
        var policy = policies.findByWorkspace_Id(target.getWorkspace().getId())
                .filter(value -> value.getStatus() == DirectoryPolicy.Status.ACTIVE)
                .orElseThrow(() -> unavailable("Approve an active directory policy first"));
        if (groups.isEmpty() || !policy.getApprovedGroupIds().containsAll(groups))
            throw unavailable("Select currently approved directory groups");
        if (target.isAuthorityHeld() && !sameSource(policy, target))
            throw unavailable("End the existing target before changing its directory source");
        target.setRegistrationId(policy.getRegistrationId());
        target.setIssuer(policy.getIssuer());
        target.setDirectoryProviderId(policy.getIdentityProviderId());
        target.setRequestTeamId(null);
    }

    // Unavailable evidence is a normal hold, not a rollback of independently confirmed departures.
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public GitHubAccessEvidence.Eligibility read(GitHubAccessTarget target, boolean draft) {
        long workspaceId = target.getWorkspace().getId();
        DirectoryPolicy policy = policies.findByWorkspace_Id(workspaceId)
                .orElseThrow(
                        () -> unavailable("Configure and approve a directory policy before managing GitHub access"));
        Set<String> groups = draft ? target.getDraftGroupIds() : target.getApprovedGroupIds();
        if (policy.getStatus() != DirectoryPolicy.Status.ACTIVE || !sameSource(policy, target))
            throw unavailable(
                    "The approved directory source is paused, ended or replaced; GitHub grants require an active matching source");
        var source = sources.approvedSourceForUpdate(Objects.requireNonNull(target.getRegistrationId()))
                .filter(value -> Objects.equals(value.providerId(), target.getDirectoryProviderId())
                        && value.issuer().equals(target.getIssuer()))
                .orElseThrow(
                        () -> unavailable(
                                "The directory source is no longer approved; ask the operator to restore it or end this GitHub target"));
        DirectorySnapshot snapshot = policy.getActiveSnapshot();
        if (groups.isEmpty()
                || !policy.getApprovedGroupIds().containsAll(groups)
                || !policyService.usable(policy, source, snapshot, policy.getApprovedGroupIds()))
            throw unavailable(
                    "Refresh the approved directory evidence and ensure the target uses currently approved groups before changing GitHub access");
        connections
                .findByIdAndWorkspaceId(policy.getConnectionId(), workspaceId)
                .filter(connection -> connection.getKind() == IntegrationKind.KEYCLOAK_DIRECTORY
                        && connection.getState() == IntegrationState.ACTIVE)
                .orElseThrow(
                        () -> unavailable(
                                "The directory connection is unavailable; existing GitHub access remains until eligibility is confirmed"));
        DirectorySnapshot evidence = Objects.requireNonNull(snapshot);
        Map<String, Set<String>> eligible = evidence.eligibleSubjects().entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(groups::contains))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<String> departed = new HashSet<>(evidence.confirmedDepartures());
        evidence.eligibleSubjects().keySet().stream()
                .filter(subject -> !eligible.containsKey(subject))
                .forEach(departed::add);
        var linked = identities.accountsForSubjects(source.providerId(), eligible.keySet());
        Set<Long> enrolled = memberships.findByWorkspace_Id(workspaceId).stream()
                .filter(member -> member.isActiveAt(clock.instant()))
                .map(member -> member.getAccountId())
                .collect(Collectors.toSet());
        Long githubProvider =
                providers.findProviderId("GITHUB", "https://github.com").orElse(null);
        List<GitHubAccessEvidence.Candidate> candidates = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (var entry :
                linked.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            var account = entry.getValue();
            if (!account.active() || !enrolled.contains(account.id()) || !seen.add(account.id())) continue;
            var links = identities.activeLinksForAccount(account.id());
            var directoryLink = links.stream()
                    .filter(link -> link.gitProviderId() == source.providerId()
                            && link.subject().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);
            if (directoryLink == null) continue;
            var githubLink = links.stream()
                    .filter(link -> link.gitProviderId().equals(githubProvider))
                    .findFirst()
                    .orElse(null);
            Long githubUserId = githubLink == null ? null : positiveId(githubLink.subject());
            candidates.add(new GitHubAccessEvidence.Candidate(
                    account.id(),
                    account.displayName(),
                    directoryLink.identityLinkId(),
                    directoryLink.subject(),
                    githubUserId == null || githubLink == null ? null : githubLink.identityLinkId(),
                    githubUserId,
                    null,
                    null));
        }
        candidates.sort(Comparator.comparingLong(GitHubAccessEvidence.Candidate::accountId));
        return new GitHubAccessEvidence.Eligibility(
                GitHubAccessTarget.Source.DIRECTORY,
                policy.getConfigurationVersion(),
                evidence.startedAt(),
                source.updatedAt(),
                groups,
                evidence.groupNames(),
                candidates,
                departed);
    }

    /** Directory capture holds the provider before accounts, including across different workspaces. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSource(GitHubAccessTarget target) {
        sources.approvedSourceForUpdate(Objects.requireNonNull(target.getRegistrationId()));
    }

    /** An explicit end/replacement is intent, unlike a failed read or a disabled operator source. */
    @Transactional(readOnly = true)
    public boolean sourceEnded(GitHubAccessTarget target) {
        return policies.findByWorkspace_Id(target.getWorkspace().getId())
                .map(policy -> policy.getStatus() == DirectoryPolicy.Status.ENDED || !sameSource(policy, target))
                .orElse(false);
    }

    private boolean sameSource(DirectoryPolicy policy, GitHubAccessTarget target) {
        return policy.getRegistrationId().equals(target.getRegistrationId())
                && policy.getIssuer().equals(target.getIssuer())
                && policy.getIdentityProviderId().equals(target.getDirectoryProviderId());
    }

    private static @Nullable Long positiveId(String subject) {
        try {
            long id = Long.parseLong(subject);
            return id > 0 ? id : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static IllegalArgumentException unavailable(String reason) {
        return new IllegalArgumentException(reason);
    }
}
