package de.tum.cit.aet.hephaestus.core.security;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets.systemSecrets;
import static de.tum.cit.aet.hephaestus.testconfig.TestSystemSecrets.systemSecretsWithPrior;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pass of a rotation whose active key is the shared test key and whose prior key is a second key. The rows of this
 * test start under the prior key, so the pass moves them to the key that every other test reads.
 */
class SystemKeyRotationIntegrationTest extends BaseIntegrationTest {

    private static final String ACTIVE_KEY = "test-encryption-key-32-bytes-aes";
    private static final String PRIOR_KEY = "fedcba9876543210fedcba9876543210";

    private final SystemSecrets priorKeyOnly = systemSecrets(PRIOR_KEY, "test");
    private final SystemSecrets rotating = systemSecretsWithPrior(ACTIVE_KEY, PRIOR_KEY, "test");

    @Autowired
    private List<SystemSecretOwner> owners;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private SystemSecrets secrets;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LoginProviderRepository providers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldSealEveryValueWithTheActiveKeyAndChangeNothingOnASecondPass() {
        var connection = LlmCatalogTestFixtures.connection("rotation-" + UUID.randomUUID());
        connection.replaceApiKey("provider-key", priorKeyOnly);
        long connectionId = connections.saveAndFlush(connection).getId();
        var provider = provider("rotation-" + UUID.randomUUID());
        provider.replaceClientSecret("client-secret", priorKeyOnly);
        long providerId = providers.saveAndFlush(provider).getId();
        try {
            assertRotation(connectionId, providerId);
        } finally {
            // Other test contexts know only the active key, so no row of this test outlives it.
            connections.deleteById(connectionId);
            providers.deleteById(providerId);
        }
    }

    private void assertRotation(long connectionId, long providerId) {
        rotate();

        String apiKey = apiKey(connectionId);
        String clientSecret = clientSecret(providerId);
        assertThat(secrets.isSealedWithActiveKey(apiKey)).isTrue();
        assertThat(secrets.isSealedWithActiveKey(clientSecret)).isTrue();
        assertThat(connections.findById(connectionId).orElseThrow().apiKey(secrets))
                .isEqualTo("provider-key");
        assertThat(providers.findById(providerId).orElseThrow().clientSecret(secrets))
                .isEqualTo("client-secret");

        rotate();

        assertThat(apiKey(connectionId)).isEqualTo(apiKey);
        assertThat(clientSecret(providerId)).isEqualTo(clientSecret);
    }

    @Test
    void shouldNotOpenAClientSecretCopiedToAnotherProvider() {
        var source = provider("source-" + UUID.randomUUID());
        source.replaceClientSecret("source-secret", secrets);
        long sourceId = providers.saveAndFlush(source).getId();
        var target = provider("target-" + UUID.randomUUID());
        target.replaceClientSecret("target-secret", secrets);
        long targetId = providers.saveAndFlush(target).getId();

        try {
            jdbc.update("UPDATE login_provider SET client_secret = ? WHERE id = ?", clientSecret(sourceId), targetId);

            var moved = providers.findById(targetId).orElseThrow();
            assertThatThrownBy(() -> moved.clientSecret(secrets)).isInstanceOf(EncryptionException.class);
        } finally {
            providers.deleteById(sourceId);
            providers.deleteById(targetId);
        }
    }

    private void rotate() {
        var rotation = new SystemKeyRotation(owners, rotating);
        transactions.executeWithoutResult(status -> rotation.rotate());
    }

    private String apiKey(long connectionId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT api_key FROM llm_connection WHERE id = ?", String.class, connectionId));
    }

    private String clientSecret(long providerId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT client_secret FROM login_provider WHERE id = ?", String.class, providerId));
    }

    private static LoginProvider provider(String registrationId) {
        var provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(LoginProvider.ProviderType.GITLAB);
        provider.setDisplayName(registrationId);
        provider.setBaseUrl("https://" + registrationId + ".example.com");
        provider.setClientId("client-id");
        provider.setScopes("read_user");
        provider.setEnabled(false);
        return provider;
    }
}
