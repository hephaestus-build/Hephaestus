package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@WorkspaceAgnostic("Accounts are instance principals; a name lookup spans workspaces by design")
class AccountSummaryQueryService implements AccountSummaryQuery {
    private final AccountRepository accounts;

    AccountSummaryQueryService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, AccountSummary> findAllByIds(Collection<Long> accountIds) {
        if (accountIds.isEmpty()) return Map.of();
        return accounts.findAllByIdInAndStatusNot(accountIds, Account.Status.DELETED).stream()
                .map(a ->
                        new AccountSummary(Objects.requireNonNull(a.getId()), a.getDisplayName(), a.getPrimaryEmail()))
                .collect(Collectors.toMap(AccountSummary::id, s -> s));
    }
}
