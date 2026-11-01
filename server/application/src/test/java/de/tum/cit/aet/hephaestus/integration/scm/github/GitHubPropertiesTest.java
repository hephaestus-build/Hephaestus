package de.tum.cit.aet.hephaestus.integration.scm.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Unit tests for GitHub configuration properties validation.
 *
 * @see GitHubProperties
 */
@Tag("unit")
class GitHubPropertiesTest {

    @EnableConfigurationProperties(GitHubProperties.class)
    static class TestConfiguration {}

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class, ValidationAutoConfiguration.class);
    }

    @Nested
    class ValidConfiguration {

        @Test
        @DisplayName("should bind default properties when no configuration provided")
        void defaultConfig_contextLoads() {
            contextRunner().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(GitHubProperties.class);

                GitHubProperties props = context.getBean(GitHubProperties.class);
                // Verify defaults
                assertThat(props.app()).isNotNull();
                assertThat(props.app().id()).isEqualTo(0L); // disabled by default
            });
        }

        @Test
        @DisplayName("should bind GitHub App configuration when provided")
        void appConfig_boundCorrectly() {
            contextRunner()
                    .withPropertyValues(
                            "hephaestus.integration.github.app.id=123456",
                            "hephaestus.integration.github.app.private-key=-----BEGIN RSA PRIVATE KEY-----")
                    .run(context -> {
                        GitHubProperties props = context.getBean(GitHubProperties.class);

                        assertThat(props.app().id()).isEqualTo(123456L);
                        assertThat(props.app().privateKey()).isEqualTo("-----BEGIN RSA PRIVATE KEY-----");
                    });
        }

        @Test
        void nullPrivateKey_allowed() {
            contextRunner()
                    .withPropertyValues("hephaestus.integration.github.app.id=0")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        GitHubProperties props = context.getBean(GitHubProperties.class);
                        assertThat(props.app().privateKey()).isNull();
                        assertThat(props.app().privateKeyLocation()).isNull();
                    });
        }
    }

    @Nested
    class Validation {

        @ParameterizedTest
        @ValueSource(longs = {0, 1, 12345, 999999999})
        void validAppId_passes(long appId) {
            contextRunner()
                    .withPropertyValues("hephaestus.integration.github.app.id=" + appId)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @ParameterizedTest
        @ValueSource(longs = {-1, -100, -999999})
        void negativeAppId_validationFails(long appId) {
            contextRunner()
                    .withPropertyValues("hephaestus.integration.github.app.id=" + appId)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Nested
    class NestedRecordDefaults {

        @Test
        void nestedApp_neverNull() {
            contextRunner().run(context -> {
                GitHubProperties props = context.getBean(GitHubProperties.class);
                assertThat(props.app()).isNotNull();
            });
        }
    }
}
