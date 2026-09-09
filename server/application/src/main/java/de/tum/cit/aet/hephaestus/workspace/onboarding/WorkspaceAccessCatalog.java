package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.LoginProviderQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScope;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScopeResolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** The only roster exposed to an applicant is the owner's explicitly selected teams and maintainers. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceAccessCatalog {
    private final LoginProviderQuery loginProviders;
    private final IdentityProviderRepository identityProviders;
    private final AccountIdentityQuery identities;
    private final WorkspaceTeamScopeResolver scopes;
    private final TeamRepository teams;
    private final WorkspaceAccountMembershipRepository memberships;

    LoginProviderQuery.Provider primary(Workspace workspace, WorkspaceAccessPolicySettings settings) {
        var provider = provider(settings.primaryRegistrationId());
        if (!Set.of("GITHUB", "GITLAB").contains(provider.type())
                || !scope(workspace).providerId().equals(providerId(provider))) {
            throw conflict("Select the sign-in provider for this workspace's exact GitHub or GitLab instance");
        }
        return provider;
    }

    void validatePolicy(Workspace workspace, WorkspaceAccessPolicySettings settings) {
        primary(workspace, settings);
        var seen = new HashSet<String>();
        for (var requirement : settings.requiredLinks()) {
            var provider = provider(requirement.registrationId());
            if (!Set.of("OIDC", "SLACK", "OUTLINE").contains(provider.type())
                    || !seen.add(requirement.registrationId())) {
                throw conflict("Required links must name distinct institutional, Slack or Outline providers");
            }
            boolean teamScoped = Set.of("SLACK", "OUTLINE").contains(provider.type());
            if (teamScoped
                    != (requirement.teamId() != null && !requirement.teamId().isBlank())) {
                throw conflict("Slack and Outline require an exact team ID; institutional OIDC does not use a team ID");
            }
        }
        if (settings.reminderDays() >= settings.maximumDurationDays()) {
            throw conflict("The reminder interval must be shorter than the maximum access duration");
        }
        if (settings.notices().stream()
                        .map(WorkspaceAccessPolicySettings.PolicyNoticeDTO::key)
                        .distinct()
                        .count()
                != settings.notices().size()) throw conflict("Policy notice keys must be unique");
        if (new HashSet<>(settings.requestableTeamIds()).size()
                != settings.requestableTeamIds().size()) {
            throw conflict("Requestable teams must be unique");
        }
        team(workspace, settings.maintainerTeamId());
        for (var teamId : settings.requestableTeamIds()) team(workspace, teamId);
    }

    Long primaryLink(Workspace workspace, WorkspaceAccessPolicySettings settings, Long accountId) {
        Long providerId = providerId(primary(workspace, settings));
        return identities.activeLinksForAccount(accountId).stream()
                .filter(link -> link.gitProviderId().equals(providerId) && link.teamId() == null)
                .findFirst()
                .map(AccountIdentityQuery.IdentityLinkView::identityLinkId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.PRECONDITION_REQUIRED,
                        "Connect the workspace's primary GitHub or GitLab identity first"));
    }

    List<Long> requiredIdentityLinks(Workspace workspace, WorkspaceAccessPolicySettings settings, Long accountId) {
        var result = new ArrayList<Long>();
        result.add(primaryLink(workspace, settings, accountId));
        var links = identities.activeLinksForAccount(accountId);
        for (var requirement : settings.requiredLinks()) {
            Long providerId = providerId(provider(requirement.registrationId()));
            var link = links.stream()
                    .filter(candidate -> candidate.gitProviderId().equals(providerId)
                            && Objects.equals(candidate.teamId(), requirement.teamId()))
                    .findFirst()
                    .orElseThrow(() -> conflict("Connect every required account for its configured instance and team"));
            result.add(link.identityLinkId());
        }
        return List.copyOf(result);
    }

    List<WorkspaceAccessTeamOptionDTO> teamOptions(Workspace workspace, WorkspaceAccessPolicySettings settings) {
        return settings.requestableTeamIds().stream()
                .map(id -> team(workspace, id))
                .map(team -> new WorkspaceAccessTeamOptionDTO(team.getId(), team.getName()))
                .toList();
    }

    List<WorkspaceAccessMaintainerOptionDTO> maintainers(Workspace workspace, WorkspaceAccessPolicySettings settings) {
        var team = team(workspace, settings.maintainerTeamId());
        var accountIds = new HashSet<Long>();
        for (var membership : team.getMemberships()) {
            var user = membership.getUser();
            identities
                    .resolveActiveAccountId(
                            Objects.requireNonNull(user.getProvider().getId()),
                            user.getNativeId().toString(),
                            null)
                    .ifPresent(accountIds::add);
        }
        return memberships.findByWorkspace_Id(workspace.getId()).stream()
                .filter(member -> member.isActive() && accountIds.contains(member.getAccountId()))
                .map(member -> identities.account(member.getAccountId()))
                .flatMap(java.util.Optional::stream)
                .filter(AccountIdentityQuery.AccountView::active)
                .map(account -> new WorkspaceAccessMaintainerOptionDTO(account.id(), account.displayName()))
                .sorted(java.util.Comparator.comparing(WorkspaceAccessMaintainerOptionDTO::displayName)
                        .thenComparing(WorkspaceAccessMaintainerOptionDTO::accountId))
                .toList();
    }

    List<WorkspaceAccessLinkOptionDTO> linkOptions(WorkspaceAccessPolicySettings settings, Long accountId) {
        var links = identities.activeLinksForAccount(accountId);
        return settings.requiredLinks().stream()
                .map(requirement -> {
                    var provider = provider(requirement.registrationId());
                    var providerId = identityProviders
                            .findByTypeAndServerUrl(IdentityProviderType.valueOf(provider.type()), provider.serverUrl())
                            .map(value -> value.getId());
                    boolean linked = providerId.isPresent()
                            && links.stream()
                                    .anyMatch(link -> link.gitProviderId().equals(providerId.get())
                                            && Objects.equals(link.teamId(), requirement.teamId()));
                    return new WorkspaceAccessLinkOptionDTO(
                            provider.registrationId(),
                            provider.displayName(),
                            provider.type(),
                            provider.serverUrl(),
                            requirement.teamId(),
                            linked);
                })
                .toList();
    }

    private WorkspaceTeamScope scope(Workspace workspace) {
        return scopes.resolve(workspace)
                .orElseThrow(
                        () -> conflict("Synchronize the workspace organization before configuring access requests"));
    }

    private Team team(Workspace workspace, Long id) {
        return teams.findById(id)
                .filter(scope(workspace)::contains)
                .orElseThrow(() ->
                        conflict("Every selected team must belong to this workspace's provider and organization"));
    }

    private LoginProviderQuery.Provider provider(String registrationId) {
        return loginProviders
                .findEnabled(registrationId)
                .orElseThrow(() -> conflict("A configured sign-in or linking provider is unavailable"));
    }

    private Long providerId(LoginProviderQuery.Provider provider) {
        return identityProviders
                .findByTypeAndServerUrl(IdentityProviderType.valueOf(provider.type()), provider.serverUrl())
                .map(value -> Objects.requireNonNull(value.getId()))
                .orElseThrow(() -> conflict("A configured identity provider has not been initialized"));
    }

    static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    record WorkspaceAccessTeamOptionDTO(
            @NonNull Long id, @NonNull String name) {}

    record WorkspaceAccessMaintainerOptionDTO(
            @NonNull Long accountId, @NonNull String displayName) {}

    record WorkspaceAccessLinkOptionDTO(
            @NonNull String registrationId,
            @NonNull String displayName,
            @NonNull String providerType,
            @NonNull String serverUrl,
            @Nullable String teamId,
            boolean linked) {}
}
