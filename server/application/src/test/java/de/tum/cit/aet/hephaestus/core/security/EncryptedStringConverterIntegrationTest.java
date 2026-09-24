package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class EncryptedStringConverterIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private LoginProviderRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EncryptedStringConverter springConverter;

    @Autowired
    private SystemEncryptionKey systemEncryptionKey;

    @Test
    void shouldEncryptThroughHibernateAndReadThroughTheSharedSpringConfiguration() {
        var key = systemEncryptionKey.key();
        assertThat(key).isNotNull();
        assertThat(key.getEncoded()).hasSize(32);

        var provider = new LoginProvider();
        provider.setRegistrationId("encryption-" + UUID.randomUUID());
        provider.setType(LoginProvider.ProviderType.GITLAB);
        provider.setDisplayName("Encryption contract");
        provider.setBaseUrl("https://encryption-contract.example.com");
        provider.setClientId("test-client");
        provider.setClientSecret("test-client-secret");
        provider.setScopes("read_user");
        var saved = repository.saveAndFlush(provider);
        try {
            String stored = jdbc.queryForObject(
                    "SELECT client_secret FROM login_provider WHERE id = ?", String.class, saved.getId());
            assertThat(stored).isNotNull().startsWith("ENC:").doesNotContain("test-client-secret");
            assertThat(springConverter.convertToEntityAttribute(stored)).isEqualTo("test-client-secret");
            assertThat(repository.findById(saved.getId()).orElseThrow().getClientSecret())
                    .isEqualTo("test-client-secret");
        } finally {
            repository.deleteById(saved.getId());
        }
    }
}
