package de.tum.cit.aet.hephaestus.core.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties.Limit;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

class AuthRateLimitPropertiesTest extends BaseUnitTest {

    private static final Bindable<AuthRateLimitProperties> TARGET = Bindable.of(AuthRateLimitProperties.class);

    @Test
    void shouldRefuseToStartWithoutConfiguredLimits() {
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource())
                        .bindOrCreate(AuthRateLimitProperties.PREFIX, TARGET))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("hephaestus.auth.rate-limit.enabled is not set");
    }

    @Test
    void shouldRefuseToStartWhenALimitIsMissing() {
        var source = new MapConfigurationPropertySource(Map.of(
                "hephaestus.auth.rate-limit.enabled", "true",
                "hephaestus.auth.rate-limit.oauth-authorization.capacity", "20",
                "hephaestus.auth.rate-limit.oauth-authorization.period", "1m"));
        assertThatThrownBy(() -> new Binder(source).bindOrCreate(AuthRateLimitProperties.PREFIX, TARGET))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("hephaestus.auth.rate-limit.refresh is not set");
    }

    @Test
    void shouldBindTheLimitsOfTheApplicationConfiguration() throws IOException {
        var sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        AuthRateLimitProperties properties = new Binder(
                        ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bindOrCreate(AuthRateLimitProperties.PREFIX, TARGET);
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.oauthAuthorization()).isEqualTo(new Limit(20, Duration.ofMinutes(1)));
        assertThat(properties.deleteUser()).isEqualTo(new Limit(3, Duration.ofHours(1)));
        assertThat(properties.export()).isEqualTo(new Limit(10, Duration.ofHours(1)));
        assertThat(properties.browserReports()).isEqualTo(new Limit(30, Duration.ofMinutes(1)));
    }

    @Test
    void shouldRejectALimitWithoutCapacity() {
        var source = new MapConfigurationPropertySource(Map.of("limit.period", "1m"));
        assertThatThrownBy(() -> new Binder(source).bindOrCreate("limit", Bindable.of(Limit.class)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("capacity must be >= 1");
    }
}
