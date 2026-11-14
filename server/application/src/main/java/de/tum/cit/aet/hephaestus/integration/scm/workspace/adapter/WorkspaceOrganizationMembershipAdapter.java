package de.tum.cit.aet.hephaestus.integration.scm.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLocks;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceProviderAccounts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

@Component
public class WorkspaceOrganizationMembershipAdapter implements OrganizationMembershipListener {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceOrganizationMembershipAdapter.class);

    private final WorkspaceLocks workspaceLocks;

    private final WorkspaceLookup workspaceLookup;
    private final WorkspaceProviderAccounts providerAccounts;
    private final MembershipService workspaceMembershipService;
    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final WorkspaceActorSelector actorSelector;
    private final ConnectionCommands connections;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public WorkspaceOrganizationMembershipAdapter(
            WorkspaceLocks workspaceLocks,
            WorkspaceLookup workspaceLookup,
            WorkspaceProviderAccounts providerAccounts,
            MembershipService workspaceMembershipService,
            OrganizationMembershipRepository organizationMembershipRepository,
            OrganizationRepository organizationRepository,
            TeamMembershipRepository teamMembershipRepository,
            WorkspaceActorSelector actorSelector,
            ConnectionCommands connections) {
        this.workspaceLocks = workspaceLocks;
        this.workspaceLookup = workspaceLookup;
        this.providerAccounts = providerAccounts;
        this.workspaceMembershipService = workspaceMembershipService;
        this.organizationMembershipRepository = organizationMembershipRepository;
        this.organizationRepository = organizationRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.actorSelector = actorSelector;
        this.connections = connections;
    }

    /**
     * Whether members may come into {@code workspace} from the roster or the teams of the group {@code root} on the
     * provider {@code providerId}, and keeps that answer true until the caller's transaction ends. Call it before the
     * first membership write.
     *
     * <p>A GitLab group grants nobody while the workspace follows only the projects it selected: its members then
     * come from those projects (the GitLab selected-membership projection). The workspace is locked before the
     * connection's lifecycle lock, which also guards a selection change, Stop and a disconnect, so none of them can
     * change the answer before the caller's writes commit. A workspace without an active GitLab connection, or whose connection,
     * instance or group changed while this waited, takes nobody. Other providers are not affected.
     *
     * <p>When the connection names its group by id, a group known by another id grants nobody: the source is a group
     * the connection no longer reads. {@code groupId} is the source group's id when the source names it; a linked
     * workspace's organization must have that id too. Only a source that names no id is known by the linked
     * organization. A connection that names no group id, or a source whose group id is not known, is not compared.
     */
    boolean mayApplyGroupGrants(
            Workspace workspace,
            IdentityProviderType sourceType,
            long providerId,
            String root,
            @Nullable Long groupId) {
        if (sourceType != IdentityProviderType.GITLAB) {
            return true;
        }
        long workspaceId = workspace.getId();
        if (workspaceLocks.lock(workspaceId).isEmpty()) {
            return false;
        }
        Optional<ConnectionConfig> locked = connections
                .holdActiveConnection(workspaceId, IntegrationKind.GITLAB)
                .map(Connection::getConfig);
        if (locked.isEmpty()) {
            return false;
        }
        // Read what the lock now guards, not what this transaction loaded before it.
        EntityManager em = Objects.requireNonNull(entityManager);
        em.refresh(workspace);
        Long linked = workspace.getOrganizationId();
        Organization linkedGroup = linked == null
                ? null
                : organizationRepository
                        .findById(linked)
                        .filter(organization ->
                                Objects.equals(organization.getProvider().getId(), providerId)
                                        && organization.getLogin().equalsIgnoreCase(root))
                        .orElse(null);
        boolean ownsGroup = linked == null ? root.equalsIgnoreCase(workspace.getAccountLogin()) : linkedGroup != null;
        Long linkedGroupId = linkedGroup != null ? linkedGroup.getNativeId() : null;
        // The caller writes the grants of the group it names, not of the group the workspace is linked to now: a link
        // that changed while this waited must agree with a named source, never stand in for it.
        boolean sameSource = groupId == null || linkedGroupId == null || groupId.equals(linkedGroupId);
        Long knownGroupId = groupId != null ? groupId : linkedGroupId;
        Long connectedGroupId =
                locked.get() instanceof ConnectionConfig.GitLabConfig config ? config.gitlabGroupId() : null;
        boolean sameGroup = sameSource
                && (connectedGroupId == null || knownGroupId == null || connectedGroupId.equals(knownGroupId));
        return ownsGroup
                && sameGroup
                && actorSelector
                        .connectedProviderId(workspaceId)
                        .filter(connected -> connected == providerId)
                        .isPresent()
                && providerAccounts
                        .providerAccount(workspaceId)
                        .filter(policy -> policy.repositorySelection() != RepositorySelection.SELECTED)
                        .isPresent();
    }

    private boolean mayApplyGroupGrants(Workspace workspace, Organization organization) {
        return mayApplyGroupGrants(
                workspace,
                organization.getProvider().getType(),
                Objects.requireNonNull(organization.getProvider().getId()),
                organization.getLogin(),
                organization.getNativeId());
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
        Optional<Organization> organization = organizationRepository.findById(event.organizationId());

        if (workspaceOpt.isEmpty() || organization.isEmpty()) {
            log.debug("Skipped member sync: reason=noWorkspaceForOrg, organizationId={}", event.organizationId());
            return;
        }

        Workspace workspace = workspaceOpt.get();
        if (!mayApplyGroupGrants(workspace, organization.get())) {
            log.debug("Skipped member sync: reason=noGroupGrantAuthority, workspaceId={}", workspace.getId());
            return;
        }

        try {
            int synced = reconcileWorkspaceMembers(workspace, event.organizationId(), event.rosterComplete());
            log.info(
                    "Synced workspace members after scheduled org sync: workspaceId={},"
                            + " organizationId={}, memberCount={}",
                    workspace.getId(),
                    event.organizationId(),
                    synced);
        } catch (Exception e) {
            log.error(
                    "Failed to sync workspace members after scheduled sync: workspaceId={}, organizationId={}",
                    workspace.getId(),
                    event.organizationId(),
                    e);
        }
    }

    // A one-person roster event must not remove other workspace members.
    private void syncWorkspaceFromOrganization(MembershipChangedEvent event, String action) {
        Optional<Workspace> workspaceOpt = findWorkspace(event.organizationId(), event.organizationLogin());
        Optional<Organization> organization = organizationRepository.findById(event.organizationId());

        if (workspaceOpt.isEmpty() || organization.isEmpty()) {
            log.debug(
                    "Skipped member sync: reason=noWorkspaceForOrg, organizationId={}, action={}",
                    event.organizationId(),
                    action);
            return;
        }

        Workspace workspace = workspaceOpt.get();
        if (!mayApplyGroupGrants(workspace, organization.get())) {
            log.debug(
                    "Skipped member sync: reason=noGroupGrantAuthority, workspaceId={}, action={}",
                    workspace.getId(),
                    action);
            return;
        }

        try {
            Membership.@Nullable WorkspaceRole role = currentGrant(organization.get(), event.userId());
            workspaceMembershipService.applyProviderGrant(workspace, event.userId(), role);
            log.info(
                    "Synced workspace member after member change: workspaceId={}, organizationId={},"
                            + " action={}, userId={}, role={}",
                    workspace.getId(),
                    event.organizationId(),
                    action,
                    event.userId(),
                    role);
        } catch (Exception e) {
            log.error(
                    "Failed to sync workspace member after member change: workspaceId={}, organizationId={}, action={}",
                    workspace.getId(),
                    event.organizationId(),
                    action,
                    e);
            // Don't rethrow - org membership change succeeded, workspace sync is secondary
        }
    }

    private Membership.@Nullable WorkspaceRole currentGrant(Organization organization, Long userId) {
        Optional<Membership.WorkspaceRole> rosterRole = organizationMembershipRepository
                .findByOrganizationIdAndUserId(organization.getId(), userId)
                .map(membership -> mapOrgRoleToWorkspaceRole(membership.getRole()));
        if (rosterRole.isPresent()) {
            return rosterRole.get();
        }
        return teamMembershipRepository
                        .findDistinctUserIdsOfTeams(
                                organization.getLogin(),
                                Objects.requireNonNull(
                                        organization.getProvider().getId()))
                        .contains(userId)
                ? Membership.WorkspaceRole.MEMBER
                : null;
    }

    // Disconnected or re-bound organization events cannot grant workspace membership.
    private Optional<Workspace> findWorkspace(Long organizationId, String organizationLogin) {
        Optional<Long> organizationProvider = organizationRepository
                .findById(organizationId)
                .map(organization ->
                        Objects.requireNonNull(organization.getProvider().getId()));
        if (organizationProvider.isEmpty()) {
            return Optional.empty();
        }
        Optional<Workspace> linked = workspaceLookup.findByOrganization(organizationId);
        if (linked.isPresent()) {
            return linked.filter(
                    workspace -> organizationProvider.equals(actorSelector.connectedProviderId(workspace.getId())));
        }
        List<Workspace> unlinked = workspaceLookup.findByAccountLogin(organizationLogin).stream()
                .filter(workspace -> workspace.getOrganizationId() == null)
                .filter(workspace -> organizationProvider.equals(actorSelector.connectedProviderId(workspace.getId())))
                .toList();
        return unlinked.size() == 1 ? Optional.of(unlinked.getFirst()) : Optional.empty();
    }

    /**
     * Sets the workspace's members to what the provider currently grants: the organization roster's
     * roles, and MEMBER for anyone only a team of the organization lists, since a team grants no
     * administration. No role is carried over from the workspace itself except OWNER, which is kept
     * for anyone who holds it. The caller has taken {@link #mayApplyGroupGrants} in this transaction.
     *
     * @param workspace the workspace to sync
     * @param organizationId the organization ID to sync members from
     * @param rosterComplete whether an empty roster is a real answer; otherwise it is taken as a
     *     shortfall and nobody is removed
     * @return the number of members synced
     */
    int reconcileWorkspaceMembers(Workspace workspace, Long organizationId, boolean rosterComplete) {
        Long workspaceId = workspace.getId();

        List<OrganizationMembership> orgMemberships =
                organizationMembershipRepository.findByOrganizationId(organizationId);

        if (orgMemberships.isEmpty() && !rosterComplete) {
            log.debug(
                    "Skipped workspace member sync: reason=noOrgMembersFound, workspaceId={}," + " organizationId={}",
                    workspaceId,
                    organizationId);
            return 0;
        }

        Map<Long, Membership.WorkspaceRole> desiredRoles = new HashMap<>();
        for (OrganizationMembership orgMembership : orgMemberships) {
            desiredRoles.put(orgMembership.getUserId(), mapOrgRoleToWorkspaceRole(orgMembership.getRole()));
        }
        organizationRepository.findById(organizationId).ifPresent(organization -> {
            for (Long userId : teamMembershipRepository.findDistinctUserIdsOfTeams(
                    organization.getLogin(),
                    Objects.requireNonNull(organization.getProvider().getId()))) {
                desiredRoles.putIfAbsent(userId, Membership.WorkspaceRole.MEMBER);
            }
        });
        // An owner stays owner whether or not the provider still lists them.
        workspaceMembershipService.syncWorkspaceMembers(workspace, desiredRoles);

        return desiredRoles.size();
    }

    private Membership.WorkspaceRole mapOrgRoleToWorkspaceRole(OrganizationMemberRole orgRole) {
        if (orgRole == OrganizationMemberRole.ADMIN) {
            return Membership.WorkspaceRole.ADMIN;
        }
        return Membership.WorkspaceRole.MEMBER;
    }
}
