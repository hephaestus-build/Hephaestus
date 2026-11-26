package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAuthorityGenerations;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The person's authorization generation; the trigger {@code account_narrows_authority} raises it. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class AccountAuthorityGenerationsAdapter implements AccountAuthorityGenerations {

    private final AccountRepository accounts;

    @Override
    @Transactional(readOnly = true)
    public OptionalLong generation(long accountId) {
        return accounts.findAuthorityGeneration(accountId).map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OptionalLong generationHeld(long accountId) {
        return accounts.findAuthorityGenerationForShare(accountId)
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }
}
