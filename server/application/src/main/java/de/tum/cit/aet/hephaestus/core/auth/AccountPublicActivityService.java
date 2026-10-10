package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPublicActivity;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("An account's public activity choice applies in every workspace")
class AccountPublicActivityService implements AccountPublicActivity {
    private final AccountRepository accounts;

    @Override
    @Transactional(readOnly = true)
    public boolean visible(long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account", accountId))
                .isPublicActivityVisible();
    }

    @Override
    @Transactional
    public boolean setVisible(long accountId, boolean visible) {
        var account = accounts.findById(accountId).orElseThrow(() -> new EntityNotFoundException("Account", accountId));
        account.setPublicActivityVisible(visible);
        return visible;
    }
}
