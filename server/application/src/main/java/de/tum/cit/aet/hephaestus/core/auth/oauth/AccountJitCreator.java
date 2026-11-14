package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@ConditionalOnServerRole
@Component
public class AccountJitCreator {

    private final AccountRepository accountRepository;
    private final PeopleRegistry people;

    public AccountJitCreator(AccountRepository accountRepository, PeopleRegistry people) {
        this.accountRepository = accountRepository;
        this.people = people;
    }

    @Transactional
    public Account create(Account account, Identity link) {
        var key = PersonIdentity.of(link.getProviderId(), link.getProviderUserKey(), link.getTeamKey());
        long id = people.ensureHuman(key, account.getDisplayName());
        var saved = accountRepository.findById(id).orElseThrow();
        boolean unclaimed = saved.getStatus() == Account.Status.UNCLAIMED;
        people.claim(key);
        if (unclaimed) {
            saved.setDisplayName(account.getDisplayName());
            String email = account.getPrimaryEmail();
            if (email != null) {
                accountRepository.lockContactEmail(email);
                if (!accountRepository.existsByPrimaryEmailIgnoreCase(email)) {
                    saved.setPrimaryEmail(email);
                    saved.setPrimaryEmailVerifiedAt(account.getPrimaryEmailVerifiedAt());
                }
            }
        }
        return accountRepository.saveAndFlush(saved);
    }
}
