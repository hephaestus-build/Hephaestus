package de.tum.cit.aet.hephaestus.integration.scm.github.team;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermission;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermissionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.dto.GitHubTeamEventDTO;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub team webhook events.
 *
 * <p><b>Subject tiers.</b> GitHub delivers {@code team} events with two different payload shapes and
 * the generic subject deriver keys them onto two different tiers:
 * <ul>
 *   <li>{@code created}/{@code edited}/{@code deleted} carry only an {@code organization} →
 *       {@code organization.team} (this handler).</li>
 *   <li>{@code added_to_repository}/{@code removed_from_repository} additionally carry a
 *       {@code repository} → {@code repository.team}, routed by {@link GitHubTeamRepositoryMessageHandler}
 *       which delegates back into {@link #routeTeamEvent(GitHubTeamEventDTO)}.</li>
 * </ul>
 * Without the second registration the repo-permission actions would key to {@code repository.team},
 * which has no handler, and be silently ACK-dropped — team↔repo permission changes would only land at
 * the next full team sync.
 */
@Component
public class GitHubTeamMessageHandler extends AbstractIntegrationMessageHandler<GitHubTeamEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubTeamMessageHandler.class);

    private final GitHubTeamProcessor teamProcessor;
    private final ScopeIdResolver scopeIdResolver;
    private final IdentityProviderRepository gitProviderRepository;
    private final TeamRepository teamRepository;
    private final RepositoryRepository repositoryRepository;
    private final TeamRepositoryPermissionRepository permissionRepository;

    GitHubTeamMessageHandler(
            GitHubTeamProcessor teamProcessor,
            ScopeIdResolver scopeIdResolver,
            IdentityProviderRepository gitProviderRepository,
            TeamRepository teamRepository,
            RepositoryRepository repositoryRepository,
            TeamRepositoryPermissionRepository permissionRepository,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "organization." + GitHubEventType.TEAM.getValue(),
                GitHubTeamEventDTO.class,
                deserializer,
                transactionTemplate);
        this.teamProcessor = teamProcessor;
        this.scopeIdResolver = scopeIdResolver;
        this.gitProviderRepository = gitProviderRepository;
        this.teamRepository = teamRepository;
        this.repositoryRepository = repositoryRepository;
        this.permissionRepository = permissionRepository;
    }

    @Override
    protected void handleEvent(GitHubTeamEventDTO event) {
        routeTeamEvent(event);
    }

    /**
     * Shared routing for every team action, regardless of subject tier. Package-visible so the
     * {@code repository.team} sibling handler ({@link GitHubTeamRepositoryMessageHandler}) can reuse the
     * exact same dispatch for the {@code added_to_repository}/{@code removed_from_repository} deliveries
     * that arrive on the repository tier.
     */
    void routeTeamEvent(GitHubTeamEventDTO event) {
        var teamDto = event.team();

        if (teamDto == null || teamDto.id() == null) {
            log.warn("Received team event with missing data: action={}", event.action());
            return;
        }

        String orgLogin = event.organization() != null ? event.organization().login() : null;
        if (orgLogin == null) {
            log.warn("Received team event with missing organization: action={}", event.action());
            return;
        }

        log.debug(
                "Received team event: action={}, teamName={}, orgLogin={}",
                event.action(),
                sanitizeForLog(teamDto.name()),
                sanitizeForLog(orgLogin));

        Long scopeId = scopeIdResolver.findScopeIdByOrgLogin(orgLogin).orElse(null);
        if (scopeId == null) {
            log.debug("Skipped team event: reason=noAssociatedScope, orgLogin={}", sanitizeForLog(orgLogin));
            return;
        }
        IdentityProvider gitHubProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow(() -> new IllegalStateException("GitHub provider not configured"));
        ProcessingContext context = ProcessingContext.forWebhook(scopeId, gitHubProvider, event.action());

        switch (event.actionType()) {
            case GitHubEventAction.Team.DELETED -> teamProcessor.delete(teamDto.id(), context);
            case GitHubEventAction.Team.CREATED, GitHubEventAction.Team.EDITED ->
                teamProcessor.process(teamDto, orgLogin, context);
            case GitHubEventAction.Team.ADDED_TO_REPOSITORY -> handleAddedToRepository(event, gitHubProvider);
            case GitHubEventAction.Team.REMOVED_FROM_REPOSITORY -> handleRemovedFromRepository(event, gitHubProvider);
            default -> log.debug("Skipped team event: reason=unhandledAction, action={}", event.action());
        }
    }

    private void handleAddedToRepository(GitHubTeamEventDTO event, IdentityProvider provider) {
        var repoRef = event.repository();
        var teamDto = event.team();

        if (repoRef == null || repoRef.id() == null) {
            log.warn("Skipped added_to_repository: reason=noRepository, teamName={}", sanitizeForLog(teamDto.name()));
            return;
        }

        Long providerId = Objects.requireNonNull(provider.getId());

        var teamOpt = teamRepository.findByNativeIdAndProviderId(Objects.requireNonNull(teamDto.id()), providerId);
        if (teamOpt.isEmpty()) {
            log.debug(
                    "Skipped added_to_repository: reason=teamNotFound, teamNativeId={}, teamName={}",
                    teamDto.id(),
                    sanitizeForLog(teamDto.name()));
            return;
        }

        var repoOpt = repositoryRepository.findByNativeIdAndProviderId(repoRef.id(), providerId);
        if (repoOpt.isEmpty()) {
            log.debug(
                    "Skipped added_to_repository: reason=repoNotMonitored, repoNativeId={}, repoName={}",
                    repoRef.id(),
                    sanitizeForLog(repoRef.fullName()));
            return;
        }

        Team team = teamOpt.get();
        Repository repo = repoOpt.get();

        TeamRepositoryPermission.PermissionLevel level = mapWebhookPermission(teamDto.permission());

        var existingOpt = permissionRepository.findByTeam_IdAndRepository_Id(team.getId(), repo.getId());
        if (existingOpt.isPresent()) {
            TeamRepositoryPermission existing = existingOpt.get();
            if (existing.getPermission() != level) {
                existing.setPermission(level);
                permissionRepository.save(existing);
                log.info(
                        "Updated team repo permission: teamName={}, repoName={}, permission={}",
                        sanitizeForLog(team.getName()),
                        sanitizeForLog(repo.getNameWithOwner()),
                        level);
            }
        } else {
            TeamRepositoryPermission permission = new TeamRepositoryPermission(team, repo, level);
            permissionRepository.save(permission);
            log.info(
                    "Created team repo permission: teamName={}, repoName={}, permission={}",
                    sanitizeForLog(team.getName()),
                    sanitizeForLog(repo.getNameWithOwner()),
                    level);
        }
    }

    private void handleRemovedFromRepository(GitHubTeamEventDTO event, IdentityProvider provider) {
        var repoRef = event.repository();
        var teamDto = event.team();

        if (repoRef == null || repoRef.id() == null) {
            log.warn(
                    "Skipped removed_from_repository: reason=noRepository, teamName={}",
                    sanitizeForLog(teamDto.name()));
            return;
        }

        Long providerId = Objects.requireNonNull(provider.getId());

        var teamOpt = teamRepository.findByNativeIdAndProviderId(Objects.requireNonNull(teamDto.id()), providerId);
        if (teamOpt.isEmpty()) {
            log.debug(
                    "Skipped removed_from_repository: reason=teamNotFound, teamNativeId={}, teamName={}",
                    teamDto.id(),
                    sanitizeForLog(teamDto.name()));
            return;
        }

        var repoOpt = repositoryRepository.findByNativeIdAndProviderId(repoRef.id(), providerId);
        if (repoOpt.isEmpty()) {
            log.debug(
                    "Skipped removed_from_repository: reason=repoNotMonitored, repoNativeId={}, repoName={}",
                    repoRef.id(),
                    sanitizeForLog(repoRef.fullName()));
            return;
        }

        Team team = teamOpt.get();
        Repository repo = repoOpt.get();

        permissionRepository
                .findByTeam_IdAndRepository_Id(team.getId(), repo.getId())
                .ifPresentOrElse(
                        permission -> {
                            permissionRepository.delete(permission);
                            log.info(
                                    "Deleted team repo permission: teamName={}, repoName={}",
                                    sanitizeForLog(team.getName()),
                                    sanitizeForLog(repo.getNameWithOwner()));
                        },
                        () -> log.debug(
                                "Skipped removed_from_repository: reason=permissionNotFound, teamName={}, repoName={}",
                                sanitizeForLog(team.getName()),
                                sanitizeForLog(repo.getNameWithOwner())));
    }

    /**
     * GitHub webhook payloads use REST-style permission names ({@code pull}, {@code triage}, {@code
     * push}, {@code maintain}, {@code admin}), not the GraphQL enum ({@code READ}, {@code TRIAGE},
     * {@code WRITE}, {@code MAINTAIN}, {@code ADMIN}) used elsewhere.
     */
    private static TeamRepositoryPermission.PermissionLevel mapWebhookPermission(@Nullable String permission) {
        if (permission == null) {
            return TeamRepositoryPermission.PermissionLevel.READ;
        }
        return switch (permission.toLowerCase(Locale.ROOT)) {
            case "admin" -> TeamRepositoryPermission.PermissionLevel.ADMIN;
            case "maintain" -> TeamRepositoryPermission.PermissionLevel.MAINTAIN;
            case "push", "write" -> TeamRepositoryPermission.PermissionLevel.WRITE;
            case "triage" -> TeamRepositoryPermission.PermissionLevel.TRIAGE;
            default -> TeamRepositoryPermission.PermissionLevel.READ;
        };
    }
}
