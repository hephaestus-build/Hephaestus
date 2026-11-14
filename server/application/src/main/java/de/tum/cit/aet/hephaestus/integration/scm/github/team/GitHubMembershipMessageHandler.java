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
import org.jspecify.annotations.Nullable;
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
        Optional<CurrentMembership> current = currentMembership(event);
        if (current.isEmpty()) return;
        handleOnce(() -> apply(event, current.get()));
    }

    @Override
    protected void handleEvent(GitHubMembershipEventDTO event) {
        throw new IllegalStateException("A membership event reads GitHub before its write transaction; dispatch it");
    }

    /** The person's role in the team as GitHub reports it now, or none. */
    private record CurrentMembership(TeamMembership.@Nullable Role role) {}

    /**
     * The person's team membership now, read without a transaction. Empty when the event cannot be handled: it lacks
     * the team, the person or the organization, it is not an addition or removal, or no active workspace reads the
     * organization. A removal that cannot be read is applied, because removing is the safe direction.
     */
    private Optional<CurrentMembership> currentMembership(GitHubMembershipEventDTO event) {
        var memberDto = event.member();
        var teamDto = event.team();
        var organization = event.organization();
        boolean removed = event.actionType() == GitHubEventAction.Membership.REMOVED;
        if (memberDto == null || memberDto.id() == null || teamDto == null || organization == null) {
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
        if (!accessReader.isReadable(scopeId.get())) {
            // A workspace that cannot read GitHub still applies a removal: removing access is the safe direction.
            log.debug("Membership event without a GitHub read: reason=unreadable, action={}", event.action());
            return removed ? Optional.of(new CurrentMembership(null)) : Optional.empty();
        }
        try {
            return Optional.of(new CurrentMembership(accessReader
                    .teamRole(scopeId.get(), organization.login(), teamDto.slug(), memberDto.id())
                    .map(role ->
                            "maintainer".equals(role) ? TeamMembership.Role.MAINTAINER : TeamMembership.Role.MEMBER)
                    .orElse(null)));
        } catch (RuntimeException readFailed) {
            if (!removed) throw readFailed;
            log.warn(
                    "Applied team membership removal without reading GitHub: error={}",
                    readFailed.getClass().getSimpleName());
            return Optional.of(new CurrentMembership(null));
        }
    }

    private void apply(GitHubMembershipEventDTO event, CurrentMembership current) {
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

        if (current.role() != null) {
            storeMembership(team, user, current.role());
        } else {
            handleMemberRemoved(team, user);
        }
    }

    private void storeMembership(Team team, User user, TeamMembership.Role role) {
        var existing = teamMembershipRepository.findByTeam_IdAndUser_Id(team.getId(), user.getId());
        if (existing.isPresent()) {
            if (existing.get().getRole() != role) {
                existing.get().setRole(role);
                teamMembershipRepository.save(existing.get());
            }
            return;
        }
        teamMembershipRepository.save(new TeamMembership(team, user, role));
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
