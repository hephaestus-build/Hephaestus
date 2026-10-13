package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountTimeZone;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("An account's time zone applies in every workspace")
class AccountTimeZoneService implements AccountTimeZone {
    private final AccountRepository accounts;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> timeZone(long accountId) {
        return accounts.findById(accountId).map(Account::getTimeZone);
    }

    @Override
    @Transactional
    public void setTimeZone(long accountId, @Nullable String timeZone) {
        accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account", accountId))
                .setTimeZone(timeZone);
    }
}
