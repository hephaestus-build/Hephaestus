package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.spi.TeamMembershipListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapts team membership sync events from integration.scm to workspace member reconciliation.
 * <p>
 * Implements the {@link TeamMembershipListener} SPI defined by integration.scm so that the
 * workspace module can react to team membership changes without integration.scm needing to
 * know about workspace concepts.
 * <p>
 * This closes the gap where GitLab subgroup-only users (e.g. tutor maintainers on a
 * single subgroup) populate {@code team_membership} via the team sync but never appear
 * in {@code organization_membership}, and are therefore missed by
 * {@link WorkspaceOrganizationMembershipAdapter}. Without this adapter they would have
 * activity but no workspace membership row.
 */
@Component
public class WorkspaceTeamMembershipAdapter implements TeamMembershipListener {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTeamMembershipAdapter.class);

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipService workspaceMembershipService;
    private final TeamMembershipRepository teamMembershipRepository;

    public WorkspaceTeamMembershipAdapter(
            WorkspaceRepository workspaceRepository,
            WorkspaceMembershipService workspaceMembershipService,
            TeamMembershipRepository teamMembershipRepository) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMembershipService = workspaceMembershipService;
        this.teamMembershipRepository = teamMembershipRepository;
    }

    @Override
    @Transactional
    public void onTeamMembershipsSynced(TeamsSyncedEvent event) {
        if (event == null || event.scopeId() == null || event.rootGroupFullPath() == null) {
            log.debug("Skipped team membership reconciliation: reason=invalidEvent, event={}", event);
            return;
        }

        Optional<Workspace> workspaceOpt = workspaceRepository.findById(event.scopeId());
        if (workspaceOpt.isEmpty()) {
            log.debug(
                    "Skipped team membership reconciliation: reason=workspaceNotFound, scopeId={}, rootGroupFullPath={}",
                    event.scopeId(),
                    event.rootGroupFullPath());
            return;
        }

        Workspace workspace = workspaceOpt.get();

        try {
            Set<Long> userIds =
                    teamMembershipRepository.findDistinctUserIdsByTeamOrganizationIgnoreCase(event.rootGroupFullPath());

            if (userIds.isEmpty()) {
                log.debug(
                        "Skipped team membership reconciliation: reason=noTeamMembers, workspaceId={}, rootGroupFullPath={}",
                        workspace.getId(),
                        event.rootGroupFullPath());
                return;
            }

            int created = workspaceMembershipService.ensureMemberships(workspace, userIds);
            log.info(
                    "Reconciled workspace memberships from team graph: workspaceId={}, rootGroupFullPath={}, considered={}, created={}",
                    workspace.getId(),
                    event.rootGroupFullPath(),
                    userIds.size(),
                    created);
        } catch (Exception e) {
            log.error(
                    "Failed to reconcile workspace memberships from team graph: workspaceId={}, rootGroupFullPath={}",
                    workspace.getId(),
                    event.rootGroupFullPath(),
                    e);
            // Don't rethrow — team sync succeeded, workspace reconciliation is secondary.
        }
    }
}
