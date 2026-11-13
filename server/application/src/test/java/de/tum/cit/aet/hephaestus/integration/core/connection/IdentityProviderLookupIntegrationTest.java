package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class IdentityProviderLookupIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private IdentityProviderLookup lookup;

    @Test
    void shouldReadProviderMetadataAndOmitADeletedProvider() {
        String serverUrl = "https://lookup-" + UUID.randomUUID() + ".example.test";
        var provider = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, serverUrl));
        long id = Objects.requireNonNull(provider.getId());
        assertThat(lookup.findById(id))
                .contains(new IdentityProviderLookup.Provider(id, IdentityProviderType.GITLAB, serverUrl));
        providers.delete(provider);
        providers.flush();
        assertThat(lookup.findById(id)).isEmpty();
    }
}
