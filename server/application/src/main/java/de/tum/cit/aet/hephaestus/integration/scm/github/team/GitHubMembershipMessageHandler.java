package de.tum.cit.aet.hephaestus.integration.scm.github.team;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubAccessReader;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.dto.GitHubMembershipEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub membership webhook events (team member changes).
 */
@Component
public class GitHubMembershipMessageHandler extends AbstractIntegrationMessageHandler<GitHubMembershipEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubMembershipMessageHandler.class);

    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final GitHubUserProcessor userProcessor;
    private final IdentityProviderLookup identityProviders;
    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final ScopeIdResolver scopeIdResolver;
    private final GitHubAccessReader accessReader;

    GitHubMembershipMessageHandler(
            GitHubUserProcessor userProcessor,
            IdentityProviderLookup identityProviders,
            TeamRepository teamRepository,
            TeamMembershipRepository teamMembershipRepository,
            ScopeIdResolver scopeIdResolver,
            GitHubAccessReader accessReader,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "organization." + GitHubEventType.MEMBERSHIP.getValue(),
                GitHubMembershipEventDTO.class,
                deserializer,
                transactionTemplate);
        this.userProcessor = userProcessor;
        this.identityProviders = identityProviders;
        this.teamRepository = teamRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.scopeIdResolver = scopeIdResolver;
        this.accessReader = accessReader;
    }

    /**
     * The event names the team and the person whose membership changed; what is stored is the membership GitHub
     * reports now, read before the write transaction. A delivery that arrives again after a later change cannot restore
     * the older membership.
     */
    @Override
    protected void dispatchEvent(GitHubMembershipEventDTO event) {
        Optional<Boolean> member = currentMembership(event);
        if (member.isEmpty()) return;
        handleOnce(() -> apply(event, member.get()));
    }

    @Override
    protected void handleEvent(GitHubMembershipEventDTO event) {
        currentMembership(event).ifPresent(member -> apply(event, member));
    }

    /**
     * Whether the person is a member of the team now, read without a transaction. Empty when the event cannot be
     * handled: it lacks the team, the person or the organization, it is not an addition or removal, or no workspace
     * reads the organization. A removal that cannot be read is applied, because removing is the safe direction.
     */
    private Optional<Boolean> currentMembership(GitHubMembershipEventDTO event) {
        var memberDto = event.member();
        var teamDto = event.team();
        var organization = event.organization();
        boolean removed = event.actionType() == GitHubEventAction.Membership.REMOVED;
        if (memberDto == null || teamDto == null || organization == null) {
            log.warn("Received membership event with missing data: action={}", event.action());
            return Optional.empty();
        }
        if (!removed && event.actionType() != GitHubEventAction.Membership.ADDED) {
            log.debug("Skipped membership event: reason=unhandledAction, action={}", event.action());
            return Optional.empty();
        }
        Optional<Long> scopeId = scopeIdResolver.findScopeIdByOrgLogin(organization.login());
        if (scopeId.isEmpty()) {
            log.debug("Skipped membership event: reason=noWorkspace, action={}", event.action());
            return Optional.empty();
        }
        try {
            return Optional.of(accessReader
                    .teamRole(scopeId.get(), organization.login(), teamDto.slug(), memberDto.login())
                    .isPresent());
        } catch (RuntimeException readFailed) {
            if (!removed) throw readFailed;
            log.warn(
                    "Applied team membership removal without reading GitHub: error={}",
                    readFailed.getClass().getSimpleName());
            return Optional.of(false);
        }
    }

    private void apply(GitHubMembershipEventDTO event, boolean member) {
        var memberDto = event.member();
        var teamDto = event.team();

        log.debug(
                "Received membership event: action={}, userNativeId={}, teamNativeId={}, organizationNativeId={}",
                event.action(),
                memberDto.getDatabaseId(),
                teamDto.id(),
                event.organization() != null ? event.organization().id() : null);

        // Resolve GitHub provider ID for user upsert
        Long providerId = identityProviders
                .findId(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .orElseThrow(() -> new IllegalStateException("IdentityProvider not found for GitHub"));
        Long teamId = teamDto.id();
        if (teamId == null) {
            log.warn("Skipped membership event: reason=missingTeamId");
            return;
        }

        // Ensure user exists via processor
        User user = userProcessor.ensureExists(memberDto, providerId);
        if (user == null) {
            log.warn("Skipped membership event: reason=userNotFound, userNativeId={}", memberDto.getDatabaseId());
            return;
        }

        // Get the team
        // The payload carries GitHub's team id, not the stored team's.
        Team team =
                teamRepository.findByNativeIdAndProviderId(teamId, providerId).orElse(null);
        if (team == null) {
            // Team should be created by team webhook
            log.warn("Skipped membership event: reason=unknownTeam, teamId={}", teamDto.id());
            return;
        }

        if (member) {
            handleMemberAdded(team, user);
        } else {
            handleMemberRemoved(team, user);
        }
    }

    private void handleMemberAdded(Team team, User user) {
        // Check if membership already exists
        if (teamMembershipRepository.existsByTeam_IdAndUser_Id(team.getId(), user.getId())) {
            log.debug(
                    "Skipped membership creation: reason=alreadyExists, userId={}, teamName={}",
                    user.getId(),
                    sanitizeForLog(team.getName()));
            return;
        }

        // Create and persist the membership
        TeamMembership membership = new TeamMembership(team, user, TeamMembership.Role.MEMBER);
        teamMembershipRepository.save(membership);
        log.info("Created team membership: userId={}, teamName={}", user.getId(), sanitizeForLog(team.getName()));
    }

    private void handleMemberRemoved(Team team, User user) {
        // Delete the membership if it exists
        if (!teamMembershipRepository.existsByTeam_IdAndUser_Id(team.getId(), user.getId())) {
            log.debug(
                    "Skipped membership removal: reason=notFound, userId={}, teamName={}",
                    user.getId(),
                    sanitizeForLog(team.getName()));
            return;
        }

        teamMembershipRepository.deleteByTeam_IdAndUser_Id(team.getId(), user.getId());
        log.info("Removed team membership: userId={}, teamName={}", user.getId(), sanitizeForLog(team.getName()));
    }
}
