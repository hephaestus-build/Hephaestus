package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Purge is a soft delete of the workspace row, so its remembered leaderboard preferences go explicitly. */
@Component
@RequiredArgsConstructor
class HiddenFormerMemberPurgeContributor implements WorkspacePurgeContributor {

    private final HiddenFormerMemberRepository hiddenFormerMemberRepository;

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        hiddenFormerMemberRepository.deleteAllByWorkspaceId(workspaceId);
    }
}
