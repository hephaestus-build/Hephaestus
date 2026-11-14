package de.tum.cit.aet.hephaestus.integration.scm.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.TeamMembershipListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceOrganizationLookup;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Subgroup-only members can lack organization membership; workspace reconciliation must include team membership.
@Component
public class WorkspaceTeamMembershipAdapter implements TeamMembershipListener {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTeamMembershipAdapter.class);

    private final WorkspaceOrganizationLookup organizations;
    private final WorkspaceLookup workspaceLookup;
    private final MembershipService workspaceMembershipService;
    private final TeamMembershipRepository teamMembershipRepository;
    private final WorkspaceOrganizationMembershipAdapter organizationMembershipAdapter;

    public WorkspaceTeamMembershipAdapter(
            WorkspaceLookup workspaceLookup,
            WorkspaceOrganizationLookup organizations,
            MembershipService workspaceMembershipService,
            TeamMembershipRepository teamMembershipRepository,
            WorkspaceOrganizationMembershipAdapter organizationMembershipAdapter) {
        this.workspaceLookup = workspaceLookup;
        this.organizations = organizations;
        this.workspaceMembershipService = workspaceMembershipService;
        this.teamMembershipRepository = teamMembershipRepository;
        this.organizationMembershipAdapter = organizationMembershipAdapter;
    }

    @Override
    @Transactional
    public void onTeamMembershipsSynced(TeamsSyncedEvent event) {
        if (event == null || event.scopeId() == null || event.rootGroupFullPath() == null) {
            log.debug("Skipped team membership reconciliation: reason=invalidEvent, event={}", event);
            return;
        }

        Optional<Workspace> workspaceOpt = workspaceLookup.find(event.scopeId());
        if (workspaceOpt.isEmpty()) {
            log.debug(
                    "Skipped team membership reconciliation: reason=workspaceNotFound, scopeId={},"
                            + " rootGroupFullPath={}",
                    event.scopeId(),
                    event.rootGroupFullPath());
            return;
        }

        Workspace workspace = workspaceOpt.get();

        // Teams of another instance or another root group, or of a workspace that selected its projects, grant
        // nothing here, not even additions. Team events come only from GitLab and do not name the group's id.
        if (!organizationMembershipAdapter.mayApplyGroupGrants(
                workspace, IdentityProviderType.GITLAB, event.providerId(), event.rootGroupFullPath(), null)) {
            log.info(
                    "Skipped team membership reconciliation: reason=noGroupGrantAuthority,"
                            + " workspaceId={}, rootGroupFullPath={}",
                    workspace.getId(),
                    event.rootGroupFullPath());
            return;
        }
        Organization organization = organizations.find(workspace).orElse(null);

        // Once the organization's roster has been reconciled and every team's members are current,
        // the workspace
        // is reconciled against both: a team-only member whose last team dropped them leaves now,
        // not at the next
        // roster sync. Otherwise team members are only added.
        if (event.complete() && organization != null && workspace.getMembersSyncedAt() != null) {
            int members =
                    organizationMembershipAdapter.reconcileWorkspaceMembers(workspace, organization.getId(), true);
            log.info(
                    "Reconciled workspace memberships after team sync: workspaceId={},"
                            + " rootGroupFullPath={}, members={}",
                    workspace.getId(),
                    event.rootGroupFullPath(),
                    members);
            return;
        }

        try {
            Set<Long> userIds =
                    teamMembershipRepository.findDistinctUserIdsOfTeams(event.rootGroupFullPath(), event.providerId());

            if (userIds.isEmpty()) {
                log.debug(
                        "Skipped team membership reconciliation: reason=noTeamMembers,"
                                + " workspaceId={}, rootGroupFullPath={}",
                        workspace.getId(),
                        event.rootGroupFullPath());
                return;
            }

            int created = workspaceMembershipService.ensureMemberships(workspace, userIds);
            log.info(
                    "Reconciled workspace memberships from team graph: workspaceId={},"
                            + " rootGroupFullPath={}, considered={}, created={}",
                    workspace.getId(),
                    event.rootGroupFullPath(),
                    userIds.size(),
                    created);
        } catch (Exception e) {
            log.error(
                    "Failed to reconcile workspace memberships from team graph: workspaceId={},"
                            + " rootGroupFullPath={}",
                    workspace.getId(),
                    event.rootGroupFullPath(),
                    e);
            // Don't rethrow — team sync succeeded, workspace reconciliation is secondary.
        }
    }
}
