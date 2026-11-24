package de.tum.cit.aet.hephaestus.core.evaluation;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import java.util.Objects;
import java.util.UUID;

final class EvaluationFixtures {

    private EvaluationFixtures() {}

    static long developer(
            AccountRepository accounts, IdentityRepository identities, IdentityProviderRepository providers) {
        Account account = accounts.save(new Account("Evaluation fixture"));
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(
                IdentityProviderFixtures.gitHub(providers).getId()));
        identity.setProviderUserKey(UUID.randomUUID().toString());
        identities.save(identity);
        return Objects.requireNonNull(account.getId());
    }
}
