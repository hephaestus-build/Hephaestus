package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.config.CorsProperties;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientRegistry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceOriginPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pins that the dev-trigger carve-out is a SINGLE shared matcher object. Both the authorize
 * {@code permitAll} rule and the {@code requiresCsrf} skip reference {@link SecurityConfig#DEV_TRIGGER_MATCHER},
 * so they cannot drift apart.
 */
class SecurityConfigSharedMatcherTest extends BaseUnitTest {

    @Test
    void shouldExemptOnlyOneClickPostFromSessionRequirements() {
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/notifications/unsubscribe/token");
        post.setServletPath("/notifications/unsubscribe/token");
        assertThat(SecurityConfig.EMAIL_UNSUBSCRIBE_MATCHER.matches(post)).isTrue();
        for (String path : List.of(
                "/notifications/unsubscribe",
                "/notifications/unsubscribe/token/extra",
                "/user/notification-preferences")) {
            MockHttpServletRequest other = new MockHttpServletRequest("POST", path);
            other.setServletPath(path);
            assertThat(SecurityConfig.EMAIL_UNSUBSCRIBE_MATCHER.matches(other)).isFalse();
        }
        post.setMethod("GET");
        assertThat(SecurityConfig.EMAIL_UNSUBSCRIBE_MATCHER.matches(post)).isFalse();
    }

    private static ObjectProvider<InstalledClientRegistry> noClients() {
        return new StaticListableBeanFactory().getBeanProvider(InstalledClientRegistry.class);
    }

    @Test
    void shouldMatchOnlyTheThreeInstalledClientPostsWhenDecidingPublicAccessAndCsrf() {
        for (String path : List.of("/auth/client/token", "/auth/client/refresh", "/auth/client/logout")) {
            MockHttpServletRequest post = new MockHttpServletRequest("POST", path);
            post.setServletPath(path);
            assertThat(SecurityConfig.CLIENT_SESSION_MATCHER.matches(post))
                    .as(path)
                    .isTrue();
            MockHttpServletRequest get = new MockHttpServletRequest("GET", path);
            get.setServletPath(path);
            assertThat(SecurityConfig.CLIENT_SESSION_MATCHER.matches(get))
                    .as("GET " + path)
                    .isFalse();
        }
        for (String path : List.of("/auth/client/configuration", "/auth/client/other", "/auth/client/token/x")) {
            MockHttpServletRequest post = new MockHttpServletRequest("POST", path);
            post.setServletPath(path);
            assertThat(SecurityConfig.CLIENT_SESSION_MATCHER.matches(post))
                    .as(path)
                    .isFalse();
        }
        MockHttpServletRequest configuration = new MockHttpServletRequest("GET", "/auth/client/configuration");
        configuration.setServletPath("/auth/client/configuration");
        assertThat(SecurityConfig.CLIENT_CONFIGURATION_MATCHER.matches(configuration))
                .isTrue();
    }

    @Test
    void devTriggerMatcherMatchesDevPathsOnly() {
        MockHttpServletRequest dev = new MockHttpServletRequest("POST", "/api/dev/trigger-review");
        MockHttpServletRequest notDev = new MockHttpServletRequest("POST", "/user/something");

        assertThat(SecurityConfig.DEV_TRIGGER_MATCHER.matches(dev)).isTrue();
        assertThat(SecurityConfig.DEV_TRIGGER_MATCHER.matches(notDev)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev", "test", ""})
    void shouldRejectInsecureCookiesWhenTheE2eProfileIsAbsent(String profile) {
        MockEnvironment environment = new MockEnvironment();
        if (!profile.isEmpty()) {
            environment.setActiveProfiles(profile);
        }
        assertThatThrownBy(() -> new SecurityConfig(
                        new CorsProperties(List.of("https://example.com")),
                        noClients(),
                        new StaticListableBeanFactory().getBeanProvider(WorkspaceOriginPolicy.class),
                        environment,
                        false,
                        false,
                        false,
                        "HEPHAESTUS_AT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookie-secure");
    }

    @Test
    void shouldRejectInsecureCookiesWhenProdAndE2eProfilesAreBothActive() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "e2e");
        assertThatThrownBy(() -> new SecurityConfig(
                        new CorsProperties(List.of("https://example.com")),
                        noClients(),
                        new StaticListableBeanFactory().getBeanProvider(WorkspaceOriginPolicy.class),
                        environment,
                        false,
                        false,
                        false,
                        "HEPHAESTUS_AT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookie-secure");
    }

    @Test
    void shouldAllowInsecureCookiesWhenOnlyNonProductionE2eProfilesAreActive() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev", "e2e");
        assertThat(new SecurityConfig(
                        new CorsProperties(List.of("https://example.com")),
                        noClients(),
                        new StaticListableBeanFactory().getBeanProvider(WorkspaceOriginPolicy.class),
                        dev,
                        false,
                        false,
                        false,
                        "HEPHAESTUS_AT"))
                .isNotNull();
    }

    @Test
    void csrfCookieName_dropsHostPrefixOnlyWhenInsecure() {
        assertThat(SecurityConfig.csrfCookieName(true)).isEqualTo("__Host-XSRF-TOKEN");
        assertThat(SecurityConfig.csrfCookieName(false)).isEqualTo("XSRF-TOKEN");
    }
}
