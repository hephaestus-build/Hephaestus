package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A personal space ends with its owner's account: no one else could ever enter it. The erasure of an account, by the
 * person or on their request by an instance admin, ends the space ({@link WorkspaceLifecycleService#erasePersonalSpace}).
 */
@Component
@RequiredArgsConstructor
class PersonalSpaceErasureAdapter implements AccountErasureContributor {

    private final WorkspaceLifecycleService lifecycle;

    @Override
    public void eraseAccount(long accountId) {
        lifecycle.erasePersonalSpace(accountId);
    }
}
