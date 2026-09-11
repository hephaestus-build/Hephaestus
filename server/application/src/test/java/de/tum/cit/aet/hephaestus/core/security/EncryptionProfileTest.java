package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class EncryptionProfileTest extends BaseUnitTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.import=")
            .withBean(SecurityProperties.class, () -> new SecurityProperties(null, null, 1, null, null, false, 100));

    @ParameterizedTest
    @ValueSource(strings = {"test", "nonprod"})
    void shouldNotMistakeOtherProfileNamesForProduction(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(
                        SystemEncryptionKey.class, EncryptedStringConverter.class, CredentialBundleConverter.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "worker-node", "webhook-server"})
    void shouldRequireSystemEncryptionWhenProductionIsActivatedDirectlyOrThroughAGroup(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .withUserConfiguration(SystemEncryptionKey.class, EncryptedStringConverter.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "Encryption key is required in production! Set hephaestus.security.encryption-key");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "worker-node", "webhook-server"})
    void shouldRequireCredentialEncryptionWhenProductionIsActivatedDirectlyOrThroughAGroup(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(CredentialBundleConverter.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "Credential encryption key is required in production! Set hephaestus.security.credential-encryption-key");
                });
    }
}
