package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-module implementation of {@link AccountContactQuery} over the {@link Account} aggregate. */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Contact lookups are account-scoped")
public class AccountContactQueryService implements AccountContactQuery {

    private final AccountRepository accountRepository;

    public AccountContactQueryService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> verifiedPrimaryEmail(long accountId) {
        return accountRepository
                .findById(accountId)
                .filter(account -> account.getPrimaryEmailVerifiedAt() != null)
                .flatMap(account -> Optional.ofNullable(account.getPrimaryEmail()));
    }
}
