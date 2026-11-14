package de.tum.cit.aet.hephaestus.core.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties.Limit;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

class AuthRateLimitPropertiesTest extends BaseUnitTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class, ValidationAutoConfiguration.class);

    @Test
    void shouldRefuseToStartAndNameEveryMissingSetting() {
        context.run(result -> assertThat(result.getStartupFailure())
                .rootCause()
                .message()
                .contains(List.of(
                        "enabled",
                        "oauthAuthorization",
                        "refresh",
                        "clientSession",
                        "userView",
                        "deleteUser",
                        "export",
                        "mentorTurns",
                        "reviewRequest",
                        "syncTrigger",
                        "publicActivity",
                        "browserReports")));
    }

    @Test
    void shouldRefuseALimitWithoutCapacity() {
        context.withPropertyValues("hephaestus.auth.rate-limit.refresh.period=1m")
                .run(result -> assertThat(result.getStartupFailure())
                        .rootCause()
                        .hasMessage("rate-limit capacity must be >= 1, got: 0"));
    }

    /** The shipped configuration, with each environment variable unset, is the one home of the limits. */
    @Test
    void shouldBindTheLimitsOfTheApplicationConfiguration() throws IOException {
        var sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        AuthRateLimitProperties properties = new Binder(
                        ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bindOrCreate("hephaestus.auth.rate-limit", Bindable.of(AuthRateLimitProperties.class));
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.oauthAuthorization()).isEqualTo(new Limit(20, Duration.ofMinutes(1)));
        assertThat(properties.deleteUser()).isEqualTo(new Limit(3, Duration.ofHours(1)));
        assertThat(properties.export()).isEqualTo(new Limit(10, Duration.ofHours(1)));
        assertThat(properties.browserReports()).isEqualTo(new Limit(30, Duration.ofMinutes(1)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthRateLimitProperties.class)
    static class TestConfiguration {}
}
