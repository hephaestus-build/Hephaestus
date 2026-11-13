package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class IdentityProviderLookupIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private IdentityProviderLookup lookup;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    @Test
    void shouldReadProviderMetadataAndOmitADeletedProvider() {
        var session = Objects.requireNonNull(entityManager).unwrap(Session.class);
        String serverUrl = "https://lookup-" + UUID.randomUUID() + ".example.test";
        var provider = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, serverUrl));
        long id = Objects.requireNonNull(provider.getId());
        session.clear();
        assertThat(lookup.findById(id))
                .contains(new IdentityProviderLookup.Provider(id, IdentityProviderType.GITLAB, serverUrl));
        assertThat(lookup.findId(IdentityProviderType.GITLAB, serverUrl)).contains(id);
        assertThat(lookup.findId(IdentityProviderType.GITHUB, serverUrl)).isEmpty();
        assertThat(session.getStatistics().getEntityCount()).isZero();
        providers.deleteById(id);
        providers.flush();
        assertThat(lookup.findById(id)).isEmpty();
    }

    @Test
    void shouldPreferTheExactOriginAndStillReadARetainedAlias() {
        var session = Objects.requireNonNull(entityManager).unwrap(Session.class);
        String origin = "https://namespace-" + UUID.randomUUID() + ".example.test";
        var alias = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, origin + "/"));
        long aliasId = Objects.requireNonNull(alias.getId());
        session.clear();

        assertThat(lookup.findByOrigin(IdentityProviderType.GITLAB, origin))
                .contains(new IdentityProviderLookup.Provider(aliasId, IdentityProviderType.GITLAB, origin + "/"));
        assertThat(lookup.findByOrigin(IdentityProviderType.GITHUB, origin)).isEmpty();

        var exact = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, origin));
        long exactId = Objects.requireNonNull(exact.getId());
        session.clear();
        assertThat(lookup.findByOrigin(IdentityProviderType.GITLAB, origin))
                .contains(new IdentityProviderLookup.Provider(exactId, IdentityProviderType.GITLAB, origin));
        assertThat(session.getStatistics().getEntityCount()).isZero();
    }
}
