package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class AccountContactQueryService implements AccountContactQuery {
    private final AccountRepository accounts;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> verifiedEmail(Long accountId) {
        return accounts.findById(accountId)
                .filter(account ->
                        account.getStatus() == Account.Status.ACTIVE && account.getPrimaryEmailVerifiedAt() != null)
                .map(Account::getPrimaryEmail)
                .filter(address -> !address.isBlank());
    }
}
