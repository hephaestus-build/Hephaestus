package de.tum.cit.aet.hephaestus.project.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAbsorptionContributor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.project.ProjectTeamMemberRepository;
import de.tum.cit.aet.hephaestus.project.ProjectTeamRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * What else changes project teams. A workspace purge deletes them. An account claim moves the absorbed account's teams
 * after its workspace memberships arrived and before the old ones are deleted. Leaving a workspace and erasing an
 * account need nothing here: the database deletes those rows with the membership.
 */
@Component
@Order(20)
@ConditionalOnServerRole
@RequiredArgsConstructor
class ProjectTeamLifecycleAdapter implements WorkspacePurgeContributor, AccountAbsorptionContributor {
    private final ProjectTeamRepository teams;
    private final ProjectTeamMemberRepository members;

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        teams.deleteByWorkspaceId(workspaceId);
    }

    @Override
    public void absorb(long claimedAccountId, long unclaimedAccountId) {
        members.absorbAccount(claimedAccountId, unclaimedAccountId);
    }
}
