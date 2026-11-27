package de.tum.cit.aet.hephaestus.account.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.ErasesColumns;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Erases the goal with the account without adding it to the staff preview or erasure receipts. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@OwnerScope.StoredOwnerOperation
@ErasesColumns("personal_goal.account_id")
class PersonalGoalErasureAdapter implements AccountErasureContributor {
    private final OwnerScope owners;
    private final JdbcTemplate jdbc;

    @Override
    public void eraseAccount(long accountId) {
        owners.ofJob(accountId);
        jdbc.update("DELETE FROM public.personal_goal WHERE account_id = ?", accountId);
    }
}
