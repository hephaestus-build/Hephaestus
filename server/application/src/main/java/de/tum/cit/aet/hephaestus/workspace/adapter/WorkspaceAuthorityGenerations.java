package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workspace's authorization generation. The database raises it on every narrowing of a membership, whichever
 * writer removed or demoted the member ({@code membership_narrows_authority}).
 */
@Component
@RequiredArgsConstructor
class WorkspaceAuthorityGenerations implements AuthorityGenerations {

    private final WorkspaceRepository workspaces;

    /** In the caller's transaction when there is one, so a stamp and the check it covers read in one order. */
    @Override
    @Transactional(readOnly = true)
    public OptionalLong workspace(long workspaceId) {
        return workspaces
                .findAuthorityGeneration(workspaceId)
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }
}
