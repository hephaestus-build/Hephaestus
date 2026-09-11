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
}
