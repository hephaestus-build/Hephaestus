package de.tum.cit.aet.hephaestus.integration.scm.github.team;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.dto.GitHubMembershipEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
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

    GitHubMembershipMessageHandler(
            GitHubUserProcessor userProcessor,
            IdentityProviderLookup identityProviders,
            TeamRepository teamRepository,
            TeamMembershipRepository teamMembershipRepository,
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
    }

    @Override
    protected void handleEvent(GitHubMembershipEventDTO event) {
        var memberDto = event.member();
        var teamDto = event.team();

        if (memberDto == null || teamDto == null) {
            log.warn("Received membership event with missing data: action={}", event.action());
            return;
        }

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

        // Handle the membership action
        switch (event.actionType()) {
            case GitHubEventAction.Membership.ADDED -> handleMemberAdded(team, user);
            case GitHubEventAction.Membership.REMOVED -> handleMemberRemoved(team, user);
            default -> log.debug("Skipped membership event: reason=unhandledAction, action={}", event.action());
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
