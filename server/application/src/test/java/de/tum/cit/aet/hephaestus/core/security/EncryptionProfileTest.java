package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class EncryptionProfileTest extends BaseUnitTest {

    private static final String SYSTEM_KEY = "profileTestSystemKeyNotForUse001";
    private static final String CREDENTIAL_KEY = "profileTestCredentialKeyNotUse01";

    private static ApplicationContextRunner runner(
            String profile, @Nullable String systemKey, @Nullable String credentialKey) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=", "spring.profiles.active=" + profile)
                .withBean(
                        SecurityProperties.class,
                        () -> new SecurityProperties(systemKey, credentialKey, 1, null, null, false, 100, null, false))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(SystemSecrets.class, CredentialBundleConverter.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "nonprod", "prod", "prod,webhook"})
    void shouldStartInEveryProfileWithBothKeys(String profile) {
        runner(profile, SYSTEM_KEY, CREDENTIAL_KEY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "nonprod", "prod", "prod,webhook"})
    void shouldRefuseToStartInEveryProfileWithoutTheSystemKey(String profile) {
        runner(profile, null, CREDENTIAL_KEY).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageStartingWith("No system encryption key.");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "nonprod", "prod", "prod,webhook"})
    void shouldRefuseToStartInEveryProfileWithoutTheCredentialKey(String profile) {
        runner(profile, SYSTEM_KEY, null).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageStartingWith("No credential encryption key.");
        });
    }
}
