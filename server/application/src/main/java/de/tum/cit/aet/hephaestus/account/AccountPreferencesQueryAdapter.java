package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class AccountPreferencesQueryAdapter implements AccountPreferencesQuery {
    private final AccountDirectory accounts;
    private final ResearchParticipationQuery research;

    @Override
    @Transactional(readOnly = true)
    public Optional<PreferencesView> preferencesForAccount(Long accountId) {
        return accounts.find(accountId)
                .map(account ->
                        new PreferencesView(research.participates(accountId), account.feedbackDeliveryEnabled()));
    }
}
