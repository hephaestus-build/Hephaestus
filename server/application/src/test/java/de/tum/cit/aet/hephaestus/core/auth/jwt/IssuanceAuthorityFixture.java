package de.tum.cit.aet.hephaestus.core.auth.jwt;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;

/** The locked-row projection {@link JwtPrincipalFactory#forAuthority} reads, taken from an account. */
final class IssuanceAuthorityFixture {

    private IssuanceAuthorityFixture() {}

    static AccountRepository.IssuanceAuthority of(Account account) {
        return new AccountRepository.IssuanceAuthority() {
            @Override
            public String getStatus() {
                return account.getStatus().name();
            }

            @Override
            public String getAppRole() {
                return account.getAppRole().name();
            }

            @Override
            public String getDisplayName() {
                return account.getDisplayName();
            }
        };
    }
}
