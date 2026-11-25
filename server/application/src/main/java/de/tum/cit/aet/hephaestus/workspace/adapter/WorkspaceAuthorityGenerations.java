package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAuthorityGenerations;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workspace's authorization generation, which the database raises on every narrowing of a membership, whichever
 * writer removed or demoted the member ({@code membership_narrows_authority}), and each person's, which their account
 * owns.
 */
@Component
@RequiredArgsConstructor
class WorkspaceAuthorityGenerations implements AuthorityGenerations {

    private final WorkspaceRepository workspaces;
    /** Server role only, as the account module is; nothing outside the server role asks a person's generation. */
    private final ObjectProvider<AccountAuthorityGenerations> people;

    /** In the caller's transaction when there is one, so a stamp and the check it covers read in one order. */
    @Override
    @Transactional(readOnly = true)
    public OptionalLong workspace(long workspaceId) {
        return workspaces
                .findAuthorityGeneration(workspaceId)
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OptionalLong workspaceHeld(long workspaceId) {
        return workspaces
                .findAuthorityGenerationForShare(workspaceId)
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    @Override
    public OptionalLong account(long accountId) {
        return people.getObject().generation(accountId);
    }

    @Override
    public OptionalLong accountHeld(long accountId) {
        return people.getObject().generationHeld(accountId);
    }
}
