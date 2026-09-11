package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties.LoginProviderSeed;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider.ProviderType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

class AuthPropertiesTest extends BaseUnitTest {

    @Test
    void shouldBindBoundedLowRiskSessionDefaultsWhenNoOverridesAreConfigured() {
        AuthProperties properties = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("hephaestus.auth", Bindable.of(AuthProperties.class));
        assertThat(properties.accessTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.sessionMaxLifetime()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.stepUpMaxAge()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.impersonationMaxLifetime()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.cookieSecure()).isTrue();
    }

    @Test
    void shouldKeepApplicationConfigurationAlignedWithSessionDefaults() throws IOException {
        var sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        var binder = new Binder(
                ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        AuthProperties configured = binder.bindOrCreate("hephaestus.auth", Bindable.of(AuthProperties.class));
        AuthProperties defaults = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("hephaestus.auth", Bindable.of(AuthProperties.class));
        assertThat(configured.accessTtl()).isEqualTo(defaults.accessTtl());
        assertThat(configured.sessionMaxLifetime()).isEqualTo(defaults.sessionMaxLifetime());
    }

    @Test
    void shouldApplyDocumentedSessionEnvironmentOverrides() throws IOException {
        var sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource(
                "environment",
                Map.of(
                        "HEPHAESTUS_AUTH_ACCESS_TTL", "2h",
                        "HEPHAESTUS_AUTH_SESSION_MAX_LIFETIME", "3d",
                        "HEPHAESTUS_AUTH_STEP_UP_MAX_AGE", "2m")));
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        var binder = new Binder(
                ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        AuthProperties configured = binder.bindOrCreate("hephaestus.auth", Bindable.of(AuthProperties.class));
        assertThat(configured.accessTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(configured.sessionMaxLifetime()).isEqualTo(Duration.ofDays(3));
        assertThat(configured.stepUpMaxAge()).isEqualTo(Duration.ofMinutes(2));
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "/api | /api",
                "api | /api",
                "//api | /api",
                "/api/ | /api",
                "/api/v2/ | /api/v2",
                "'' | ''",
                "/ | ''",
                "'  /api/  ' | /api",
            },
            delimiterString = "|",
            emptyValue = "")
    void apiBasePath_isNormalizedToLeadingSlashNoTrailingSlash(String raw, String expected) {
        assertThat(AuthPropertiesFixture.withApiBasePath(raw).apiBasePath()).isEqualTo(expected);
    }

    @Nested
    class SeedConfiguredGate {

        private static LoginProviderSeed seed(ProviderType type, String clientId, String clientSecret) {
            return new LoginProviderSeed(type, "https://example.test", clientId, clientSecret, "");
        }

        @ParameterizedTest
        @EnumSource(ProviderType.class)
        @DisplayName("both credential halves present → configured")
        void bothHalves_isConfigured(ProviderType type) {
            LoginProviderSeed both = seed(type, "client-id", "client-secret");

            assertThat(both.configured()).isTrue();
            assertThat(both.partiallyConfigured()).isFalse();
            assertThat(both.missingCredentialField()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(ProviderType.class)
        @DisplayName("client id without a secret is NOT configured — it would seed a provider that cannot exchange")
        void clientIdWithoutSecret_isNotConfigured(ProviderType type) {
            LoginProviderSeed halfFilled = seed(type, "client-id", "  ");

            assertThat(halfFilled.configured()).isFalse();
            assertThat(halfFilled.partiallyConfigured()).isTrue();
            assertThat(halfFilled.missingCredentialField()).isEqualTo("client-secret");
        }

        @ParameterizedTest
        @EnumSource(ProviderType.class)
        @DisplayName("a secret without a client id is NOT configured either")
        void secretWithoutClientId_isNotConfigured(ProviderType type) {
            LoginProviderSeed halfFilled = seed(type, "", "client-secret");

            assertThat(halfFilled.configured()).isFalse();
            assertThat(halfFilled.partiallyConfigured()).isTrue();
            assertThat(halfFilled.missingCredentialField()).isEqualTo("client-id");
        }

        @Test
        @DisplayName("an entirely blank slot is silence, not a misconfiguration — credential-less pods still boot")
        void blankSlot_isNeitherConfiguredNorPartial() {
            LoginProviderSeed blank = seed(ProviderType.OUTLINE, "", "");

            assertThat(blank.configured()).isFalse();
            assertThat(blank.partiallyConfigured()).isFalse();
        }
    }
}
