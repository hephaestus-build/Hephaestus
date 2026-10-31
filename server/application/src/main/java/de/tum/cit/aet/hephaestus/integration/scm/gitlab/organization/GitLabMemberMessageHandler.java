package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.MembershipChangedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.dto.GitLabMemberEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitLab member webhook events for real-time group membership updates.
 * <p>
 * Processes {@code user_add_to_group}, {@code user_remove_from_group}, and
 * {@code user_update_for_group} events that are normalized to the "member"
 * event key by the webhook receiver.
 * <p>
 * This closes the gap where membership changes between scheduled syncs were
 * invisible, allowing removed users to retain access until the next restart.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabMemberMessageHandler extends AbstractIntegrationMessageHandler<GitLabMemberEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabMemberMessageHandler.class);

    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final GitLabRouteAdmission routeAdmission;
    private final GitLabUserService gitLabUserService;

    @Nullable
    private final OrganizationMembershipListener membershipListener;

    GitLabMemberMessageHandler(
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository,
            UserRepository userRepository,
            TeamRepository teamRepository,
            TeamMembershipRepository teamMembershipRepository,
            GitLabRouteAdmission routeAdmission,
            GitLabUserService gitLabUserService,
            @Nullable OrganizationMembershipListener membershipListener,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.MEMBER.getValue(),
                GitLabMemberEventDTO.class,
                deserializer,
                transactionTemplate);
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.routeAdmission = routeAdmission;
        this.gitLabUserService = gitLabUserService;
        this.membershipListener = membershipListener;
    }

    @Override
    protected void handleEvent(GitLabMemberEventDTO event) {
        String safeGroupPath = sanitizeForLog(event.groupPath());
        String safeUsername = sanitizeForLog(event.userUsername());

        log.debug(
                "Received member event: eventName={}, groupPath={}, user={}, access={}",
                event.eventName(),
                safeGroupPath,
                safeUsername,
                event.groupAccess());

        // The event only says that a membership changed: the membership stored is the one GitLab reports now, so a
        // forged or stale event can neither grant, change nor remove access. Without an admitted route nothing is
        // written.
        Optional<GitLabRouteAdmission.AdmittedRoute> route = GitLabRouteAdmission.current();
        if (route.isEmpty()) {
            log.warn("Skipped member event: reason=noAdmittedRoute, groupPath={}", safeGroupPath);
            return;
        }
        Optional<GitLabRouteAdmission.ReportedMembership> reported = GitLabRouteAdmission.reportedMembership();
        if (reported.isPresent() && routeAdmission.holdActive(route.get())) {
            applyReportedMembership(event.userId(), route.get(), reported.get());
        }
    }

    /**
     * Stores the membership GitLab reported for {@code userId}: in the connected group as an organization membership
     * at the highest effective access; in a subgroup as a team membership at the highest access the team sync also
     * reads (see {@link GitLabGroupMemberResponse}). No reported access removes the membership. Either way the person's workspace
     * membership then follows what the connected group's roster and teams still grant them.
     */
    void applyReportedMembership(
            long userId, GitLabRouteAdmission.AdmittedRoute route, GitLabRouteAdmission.ReportedMembership membership) {
        long providerId = route.providerId();
        GitLabGroupMemberResponse highest = membership.members().stream()
                .max(Comparator.comparingInt(GitLabMemberMessageHandler::accessLevel))
                .orElse(null);
        if (membership.groupId() == route.groupId()) {
            Organization org = organizationRepository
                    .findByNativeIdAndProviderId(membership.groupId(), providerId)
                    .orElse(null);
            if (org == null) {
                log.debug("Organization not yet synced, skipping member event: groupId={}", membership.groupId());
                return;
            }
            if (highest == null) {
                userRepository.findByNativeIdAndProviderId(userId, providerId).ifPresent(user -> {
                    // No effective access to the group is no direct grant in it
                    // either, so the group's own team,
                    // which would otherwise keep them in the workspace until the
                    // next team sync, drops them too.
                    teamRepository
                            .findByNativeIdAndProviderId(membership.groupId(), providerId)
                            .ifPresent(team ->
                                    teamMembershipRepository.deleteByTeam_IdAndUser_Id(team.getId(), user.getId()));
                    removeMember(org, user);
                });
                return;
            }
            User user = gitLabUserService.findOrCreateReportedUser(userId, providerId);
            if (user == null) {
                return;
            }
            OrganizationMemberRole role = GitLabGroupMemberSyncService.mapAccessLevel(highest.accessLevel());
            membershipRepository.upsertMembership(org.getId(), user.getId(), role);
            log.info("Added/updated group member: orgId={}, userId={}, role={}", org.getId(), user.getId(), role);
            if (membershipListener != null) {
                membershipListener.onMemberAdded(new MembershipChangedEvent(
                        org.getId(),
                        org.getLogin(),
                        user.getId(),
                        user.getLogin(),
                        accessName(Objects.requireNonNull(highest))));
            }
            return;
        }
        Team team = teamRepository
                .findByNativeIdAndProviderId(membership.groupId(), providerId)
                .orElse(null);
        if (team == null) {
            log.debug("Team not yet synced, skipping member event: groupId={}", membership.groupId());
            return;
        }
        TeamMembership.Role role = highest == null ? null : GitLabTeamSyncService.mapAccessLevel(accessName(highest));
        Optional<Organization> connectedGroup =
                organizationRepository.findByNativeIdAndProviderId(route.groupId(), providerId);
        if (role == null) {
            userRepository.findByNativeIdAndProviderId(userId, providerId).ifPresent(user -> {
                teamMembershipRepository.deleteByTeam_IdAndUser_Id(team.getId(), user.getId());
                connectedGroup.ifPresent(org -> {
                    if (membershipListener != null) {
                        membershipListener.onMemberRemoved(new MembershipChangedEvent(
                                org.getId(), org.getLogin(), user.getId(), user.getLogin(), null));
                    }
                });
            });
            return;
        }
        User user = gitLabUserService.findOrCreateReportedUser(userId, providerId);
        if (user == null) {
            return;
        }
        TeamMembership teamMembership = teamMembershipRepository
                .findById(new TeamMembership.Id(team.getId(), user.getId()))
                .orElseGet(() -> new TeamMembership(team, user, role));
        teamMembership.setRole(role);
        teamMembershipRepository.save(teamMembership);
        log.info("Added/updated team member: teamId={}, role={}", team.getId(), role);
        connectedGroup.ifPresent(org -> {
            if (membershipListener != null) {
                membershipListener.onMemberAdded(new MembershipChangedEvent(
                        org.getId(),
                        org.getLogin(),
                        user.getId(),
                        user.getLogin(),
                        accessName(Objects.requireNonNull(highest))));
            }
        });
    }

    private void removeMember(Organization org, User user) {
        membershipRepository.deleteByOrganizationIdAndUserIdIn(org.getId(), List.of(user.getId()));
        log.info("Removed group member: orgId={}, userId={}", org.getId(), user.getId());
        if (membershipListener != null) {
            membershipListener.onMemberRemoved(
                    new MembershipChangedEvent(org.getId(), org.getLogin(), user.getId(), user.getLogin(), null));
        }
    }

    private static int accessLevel(GitLabGroupMemberResponse member) {
        GitLabGroupMemberResponse.GitLabAccessLevel level = member.accessLevel();
        Integer value = level != null ? level.integerValue() : null;
        return value != null ? value : 0;
    }

    private static @Nullable String accessName(GitLabGroupMemberResponse member) {
        GitLabGroupMemberResponse.GitLabAccessLevel level = member.accessLevel();
        return level != null ? level.stringValue() : null;
    }
}
