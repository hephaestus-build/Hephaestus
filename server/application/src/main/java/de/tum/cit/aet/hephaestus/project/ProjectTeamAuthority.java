package de.tum.cit.aet.hephaestus.project;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.project.spi.ProjectTeams;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectTeamAuthority implements ProjectTeams {
    private final WorkspaceMemberships workspaceMembers;
    private final ProjectTeamMemberRepository members;

    @Override
    public boolean isCurrentMember(long workspaceId, UUID teamId, long accountId) {
        // Both memberships are read now: the authority does not rest on the rows having been cleaned up.
        return workspaceMembers.activeRole(workspaceId, accountId).isPresent()
                && members.isInStandingTeam(workspaceId, teamId, accountId);
    }
}
