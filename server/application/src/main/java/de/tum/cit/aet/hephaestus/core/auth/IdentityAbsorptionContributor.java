package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAbsorptionContributor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(40)
@ConditionalOnServerRole
@RequiredArgsConstructor
public class IdentityAbsorptionContributor implements AccountAbsorptionContributor {
    private final IdentityRepository identities;

    @Override
    public void absorb(long claimedAccountId, long unclaimedAccountId) {
        identities.absorbAccount(claimedAccountId, unclaimedAccountId);
    }
}
