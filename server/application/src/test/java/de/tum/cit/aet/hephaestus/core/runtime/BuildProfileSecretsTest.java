package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

class BuildProfileSecretsTest extends BaseUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldNotProvideKnownWebhookOrOAuthSecrets(String profile) {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment()
                        .getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=", "spring.profiles.active=" + profile, "WEBHOOK_SECRET=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("hephaestus.webhook.secret"))
                            .isNullOrEmpty();
                    assertThat(environment.getProperty("hephaestus.integration.oauth-state.secret"))
                            .isNullOrEmpty();
                    if (profile.equals("specs")) {
                        assertThat(environment.getProperty("hephaestus.integration.slack.enabled", Boolean.class))
                                .isTrue();
                        assertThat(environment.getProperty("hephaestus.integration.slack.client-id"))
                                .isEmpty();
                        assertThat(environment.getProperty("hephaestus.integration.slack.client-secret"))
                                .isEmpty();
                    }
                });
    }

    /**
     * A shipped profile that carried an encryption key would let a server start with a key anyone can read. Only the
     * image build's training run gets fixture keys, and {@link CdsTrainingRunGuard} keeps that profile in that run.
     */
    @ParameterizedTest
    @ValueSource(strings = {"default", "prod", "e2e", "playwright", "migrate", "erasure-recovery", "specs"})
    void shouldShipNoEncryptionKeyInAnyRunnableProfile(String profile) {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment()
                        .getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=", "spring.profiles.active=" + profile)
                .run(context -> {
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("hephaestus.security.encryption-key"))
                            .isNullOrEmpty();
                    assertThat(environment.getProperty("hephaestus.security.credential-encryption-key"))
                            .isNullOrEmpty();
                });
    }
}
