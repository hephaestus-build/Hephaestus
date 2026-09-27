package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapts organization membership events from integration.scm to workspace member syncing.
 * <p>
 * This implements the {@link OrganizationMembershipListener} SPI defined by integration.scm,
 * allowing the workspace module to react to organization membership changes without
 * integration.scm needing to know about workspace concepts.
 */
@Component
public class WorkspaceOrganizationMembershipAdapter implements OrganizationMembershipListener {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceOrganizationMembershipAdapter.class);

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final WorkspaceMembershipService workspaceMembershipService;
    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final WorkspaceActorSelector actorSelector;

    public WorkspaceOrganizationMembershipAdapter(
            WorkspaceRepository workspaceRepository,
            WorkspaceMembershipRepository workspaceMembershipRepository,
            WorkspaceMembershipService workspaceMembershipService,
            OrganizationMembershipRepository organizationMembershipRepository,
            OrganizationRepository organizationRepository,
            TeamMembershipRepository teamMembershipRepository,
            WorkspaceActorSelector actorSelector) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.workspaceMembershipService = workspaceMembershipService;
        this.organizationMembershipRepository = organizationMembershipRepository;
        this.organizationRepository = organizationRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.actorSelector = actorSelector;
    }

    @Override
    @Transactional
    public void onMemberAdded(MembershipChangedEvent event) {
        syncWorkspaceFromOrganization(event, "added");
    }

    @Override
    @Transactional
    public void onMemberRemoved(MembershipChangedEvent event) {
        syncWorkspaceFromOrganization(event, "removed");
    }

    @Override
    @Transactional
    public void onOrganizationMembershipsSynced(OrganizationSyncedEvent event) {
        Optional<Workspace> workspaceOpt = findWorkspace(event.organizationId(), event.organizationLogin());

        if (workspaceOpt.isEmpty()) {
            log.debug("Skipped member sync: reason=noWorkspaceForOrg, orgLogin={}", event.organizationLogin());
            return;
        }

        Workspace workspace = workspaceOpt.get();

        try {
            int synced = reconcileWorkspaceMembers(workspace, event.organizationId(), event.rosterComplete());
            log.info(
                    "Synced workspace members after scheduled org sync: workspaceId={}, orgLogin={}, memberCount={}",
                    workspace.getId(),
                    event.organizationLogin(),
                    synced);
        } catch (Exception e) {
            log.error(
                    "Failed to sync workspace members after scheduled sync: workspaceId={}, orgLogin={}",
                    workspace.getId(),
                    event.organizationLogin(),
                    e);
        }
    }

    /**
     * One person's membership changed: only their workspace membership follows, from what the stored roster and
     * subgroup teams grant them now. Nobody else is re-read, so a one-person event cannot remove anyone else.
     */
    private void syncWorkspaceFromOrganization(MembershipChangedEvent event, String action) {
        Optional<Workspace> workspaceOpt = findWorkspace(event.organizationId(), event.organizationLogin());
        Optional<Organization> organization = organizationRepository.findById(event.organizationId());

        if (workspaceOpt.isEmpty() || organization.isEmpty()) {
            log.debug(
                    "Skipped member sync: reason=noWorkspaceForOrg, orgLogin={}, action={}",
                    event.organizationLogin(),
                    action);
            return;
        }

        Workspace workspace = workspaceOpt.get();

        try {
            WorkspaceMembership.@Nullable WorkspaceRole role = currentGrant(organization.get(), event.userId());
            workspaceMembershipService.applyProviderGrant(workspace, event.userId(), role);
            log.info(
                    "Synced workspace member after member change: workspaceId={}, orgLogin={}, action={}, userLogin={}, role={}",
                    workspace.getId(),
                    event.organizationLogin(),
                    action,
                    event.userLogin(),
                    role);
        } catch (Exception e) {
            log.error(
                    "Failed to sync workspace member after member change: workspaceId={}, orgLogin={}, action={}",
                    workspace.getId(),
                    event.organizationLogin(),
                    action,
                    e);
            // Don't rethrow - org membership change succeeded, workspace sync is secondary
        }
    }

    /** The role the organization's roster or one of its subgroup teams grants {@code userId} now, if any. */
    private WorkspaceMembership.@Nullable WorkspaceRole currentGrant(Organization organization, Long userId) {
        Optional<WorkspaceMembership.WorkspaceRole> rosterRole =
                organizationMembershipRepository.findByOrganizationId(organization.getId()).stream()
                        .filter(membership -> userId.equals(membership.getUserId()))
                        .map(membership -> mapOrgRoleToWorkspaceRole(membership.getRole()))
                        .findFirst();
        if (rosterRole.isPresent()) {
            return rosterRole.get();
        }
        return teamMembershipRepository
                        .findDistinctUserIdsOfSubteams(
                                organization.getLogin(),
                                Objects.requireNonNull(
                                        organization.getProvider().getId()))
                        .contains(userId)
                ? WorkspaceMembership.WorkspaceRole.MEMBER
                : null;
    }

    /**
     * The workspace this organization's roster is for, while its active connection is on the organization's
     * instance: the one linked to the row, or else the one unlinked workspace at the same path. A queued event
     * after a disconnect or a switch of instance reaches none.
     */
    private Optional<Workspace> findWorkspace(Long organizationId, String organizationLogin) {
        Optional<Long> organizationProvider = organizationRepository
                .findById(organizationId)
                .map(organization ->
                        Objects.requireNonNull(organization.getProvider().getId()));
        if (organizationProvider.isEmpty()) {
            return Optional.empty();
        }
        Optional<Workspace> linked = workspaceRepository.findByOrganization_Id(organizationId);
        if (linked.isPresent()) {
            return linked.filter(
                    workspace -> organizationProvider.equals(actorSelector.connectedProviderId(workspace.getId())));
        }
        List<Workspace> unlinked = workspaceRepository.findAllByAccountLoginIgnoreCase(organizationLogin).stream()
                .filter(workspace -> workspace.getOrganization() == null)
                .filter(workspace -> organizationProvider.equals(actorSelector.connectedProviderId(workspace.getId())))
                .toList();
        return unlinked.size() == 1 ? Optional.of(unlinked.getFirst()) : Optional.empty();
    }

    /**
     * Sets the workspace's members to what the provider currently grants: the organization roster's roles, and
     * MEMBER for anyone only a subgroup team of the organization lists, since a team grants no administration.
     * No role is carried over from the workspace itself except OWNER, which is kept for anyone who holds it.
     *
     * @param workspace      the workspace to sync
     * @param organizationId the organization ID to sync members from
     * @param rosterComplete whether an empty roster is a real answer; otherwise it is taken as a shortfall and
     *                       nobody is removed
     * @return the number of members synced
     */
    int reconcileWorkspaceMembers(Workspace workspace, Long organizationId, boolean rosterComplete) {
        Long workspaceId = workspace.getId();

        // Get organization memberships and map to workspace roles
        List<OrganizationMembership> orgMemberships =
                organizationMembershipRepository.findByOrganizationId(organizationId);

        if (orgMemberships.isEmpty() && !rosterComplete) {
            log.debug(
                    "Skipped workspace member sync: reason=noOrgMembersFound, workspaceId={}, organizationId={}",
                    workspaceId,
                    organizationId);
            return 0;
        }

        Map<Long, WorkspaceMembership.WorkspaceRole> desiredRoles = new HashMap<>();
        for (OrganizationMembership orgMembership : orgMemberships) {
            desiredRoles.put(orgMembership.getUserId(), mapOrgRoleToWorkspaceRole(orgMembership.getRole()));
        }
        organizationRepository.findById(organizationId).ifPresent(organization -> {
            for (Long userId : teamMembershipRepository.findDistinctUserIdsOfSubteams(
                    organization.getLogin(),
                    Objects.requireNonNull(organization.getProvider().getId()))) {
                desiredRoles.putIfAbsent(userId, WorkspaceMembership.WorkspaceRole.MEMBER);
            }
        });
        // An owner stays owner whether or not the provider still lists them.
        for (WorkspaceMembership membership : workspaceMembershipRepository.findByWorkspace_Id(workspaceId)) {
            if (membership.getUser() != null && membership.getRole() == WorkspaceMembership.WorkspaceRole.OWNER) {
                desiredRoles.put(membership.getUser().getId(), WorkspaceMembership.WorkspaceRole.OWNER);
            }
        }

        // Sync workspace members
        workspaceMembershipService.syncWorkspaceMembers(workspace, desiredRoles);

        // Update sync timestamp
        workspace.setMembersSyncedAt(Instant.now());
        workspaceRepository.save(workspace);

        return desiredRoles.size();
    }

    private WorkspaceMembership.WorkspaceRole mapOrgRoleToWorkspaceRole(OrganizationMemberRole orgRole) {
        if (orgRole == OrganizationMemberRole.ADMIN) {
            return WorkspaceMembership.WorkspaceRole.ADMIN;
        }
        return WorkspaceMembership.WorkspaceRole.MEMBER;
    }
}
