package de.tum.cit.aet.hephaestus.integration.scm.identity;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class AuthenticatedGitProviderUserServiceSecurityIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private IdentityRepository identities;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private AccountIdentityQuery query;

    @Test
    @Transactional
    void shouldKeepDisabledIdentityOutOfLiveAuthority() {
        var provider = providers.save(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://" + UUID.randomUUID() + ".example"));
        var account = accounts.save(new Account("Developer"));
        long accountId = Objects.requireNonNull(account.getId());
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey("90001");
        identity.setDisabledAt(Instant.now());
        identities.saveAndFlush(identity);
        assertThat(query.activeLinksForAccount(accountId))
                .noneMatch(link -> link.identityId().equals(identity.getId()));
    }

    @Test
    @Transactional
    void shouldRetainTheExactProviderKey() {
        var provider = providers.save(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://" + UUID.randomUUID() + ".example"));
        var account = accounts.save(new Account("Developer"));
        long accountId = Objects.requireNonNull(account.getId());
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey("18025");
        identities.saveAndFlush(identity);
        assertThat(query.activeLinksForAccount(accountId)).anySatisfy(link -> {
            assertThat(link.providerId()).isEqualTo(provider.getId());
            assertThat(link.providerUserKey()).isEqualTo("18025");
        });
    }
}
