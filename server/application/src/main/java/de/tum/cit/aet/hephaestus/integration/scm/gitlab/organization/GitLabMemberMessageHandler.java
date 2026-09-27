package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.MembershipChangedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.dto.GitLabMemberEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserClassifier;
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
import org.springframework.transaction.TransactionDefinition;
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
    private final IdentityProviderRepository gitProviderRepository;
    private final GitLabProperties gitLabProperties;
    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final GitLabRouteAdmission routeAdmission;
    private final GitLabUserService gitLabUserService;

    @Nullable
    private final OrganizationMembershipListener membershipListener;

    private final TransactionTemplate requiresNewTransaction;

    GitLabMemberMessageHandler(
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository,
            UserRepository userRepository,
            IdentityProviderRepository gitProviderRepository,
            GitLabProperties gitLabProperties,
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
        this.gitProviderRepository = gitProviderRepository;
        this.gitLabProperties = gitLabProperties;
        this.teamRepository = teamRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.routeAdmission = routeAdmission;
        this.gitLabUserService = gitLabUserService;
        this.membershipListener = membershipListener;
        this.requiresNewTransaction =
                new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

        // On a connection route the event only says that a membership changed: the membership stored is the one GitLab
        // reports now, so a forged or stale event can neither grant, change nor remove access.
        Optional<GitLabRouteAdmission.AdmittedRoute> route = GitLabRouteAdmission.current();
        if (route.isPresent()) {
            Optional<GitLabRouteAdmission.ReportedMembership> reported = GitLabRouteAdmission.reportedMembership();
            if (reported.isPresent() && routeAdmission.holdActive(route.get())) {
                applyReportedMembership(event.userId(), route.get(), reported.get());
            }
            return;
        }

        Long providerId = Objects.requireNonNull(gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, gitLabProperties.defaultServerUrl())
                .orElseThrow(() -> new IllegalStateException(
                        "IdentityProvider not found for type=GITLAB, serverUrl=" + gitLabProperties.defaultServerUrl()))
                .getId());

        // Look up the organization by the group's native ID
        Organization org = organizationRepository
                .findByNativeIdAndProviderId(event.groupId(), providerId)
                .orElse(null);

        if (org == null) {
            log.debug(
                    "Organization not yet synced, skipping member event: groupId={}, groupPath={}",
                    event.groupId(),
                    safeGroupPath);
            return;
        }

        if (event.isAddition() || event.isUpdate()) {
            handleMemberAddOrUpdate(event, org, providerId);
        } else if (event.isRemoval()) {
            handleMemberRemoval(event, org);
        } else {
            log.debug("Unhandled member event action: eventName={}, groupPath={}", event.eventName(), safeGroupPath);
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
                userRepository
                        .findByNativeIdAndProviderId(userId, providerId)
                        .ifPresent(user -> removeMember(org, user));
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

    private void handleMemberAddOrUpdate(GitLabMemberEventDTO event, Organization org, Long providerId) {
        // Upsert user in isolated transaction (same pattern as GitLabGroupMemberSyncService)
        long nativeUserId = event.userId();
        String login = event.userUsername();
        String name = event.userName();

        requiresNewTransaction.executeWithoutResult(status -> {
            boolean locked = userRepository.tryAcquireLoginLock(login, providerId);
            if (locked) {
                userRepository.freeLoginConflicts(login, nativeUserId, providerId);
            }
            String avatarUrl = event.userAvatar() != null ? event.userAvatar() : "";
            userRepository.upsertUser(
                    nativeUserId,
                    providerId,
                    login,
                    name,
                    avatarUrl,
                    "", // htmlUrl not available in member webhook payload
                    GitLabUserClassifier.classify(login).name(),
                    null,
                    null,
                    null);
        });

        User user = userRepository
                .findByNativeIdAndProviderId(nativeUserId, providerId)
                .orElse(null);
        if (user == null) {
            log.warn("Failed to upsert user from member event: userId={}, login={}", nativeUserId, login);
            return;
        }

        // The event names one direct grant; an inherited one may be higher, so it never lowers the stored role.
        OrganizationMemberRole role = mapGroupAccess(event.groupAccess());
        boolean storedAdmin = membershipRepository.findByOrganizationId(org.getId()).stream()
                .anyMatch(membership -> user.getId().equals(membership.getUserId())
                        && membership.getRole() == OrganizationMemberRole.ADMIN);
        if (storedAdmin) {
            role = OrganizationMemberRole.ADMIN;
        }
        membershipRepository.upsertMembership(org.getId(), user.getId(), role);

        log.info(
                "Added/updated group member: orgId={}, orgLogin={}, userLogin={}, role={}",
                org.getId(),
                sanitizeForLog(org.getLogin()),
                sanitizeForLog(login),
                role);

        if (membershipListener != null) {
            membershipListener.onMemberAdded(
                    new MembershipChangedEvent(org.getId(), org.getLogin(), user.getId(), login, event.groupAccess()));
        }
    }

    /**
     * A removal off a connection route names one direct grant. The person may still hold the group through a parent
     * or an invited group, so it removes nothing; the next complete roster listing does, if GitLab no longer lists
     * them.
     */
    private void handleMemberRemoval(GitLabMemberEventDTO event, Organization org) {
        log.info(
                "Deferred group member removal to the next roster listing: reason=directGrantOnly, orgId={}, userLogin={}",
                org.getId(),
                sanitizeForLog(event.userUsername()));
    }

    /**
     * Maps GitLab group_access string to OrganizationMemberRole.
     * Owner and Maintainer → ADMIN; all others → MEMBER.
     */
    private static OrganizationMemberRole mapGroupAccess(@Nullable String groupAccess) {
        if (groupAccess == null) {
            return OrganizationMemberRole.MEMBER;
        }
        return switch (groupAccess) {
            case "Owner", "Maintainer" -> OrganizationMemberRole.ADMIN;
            default -> OrganizationMemberRole.MEMBER;
        };
    }
}
