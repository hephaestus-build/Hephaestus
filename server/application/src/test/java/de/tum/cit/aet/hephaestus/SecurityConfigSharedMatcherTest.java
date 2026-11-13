package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.config.CorsProperties;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientRegistry;
import de.tum.cit.aet.hephaestus.core.auth.web.PublicActivityReadRequestMatcher;
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
 * Pins the shared matchers that both the authorize rules and the {@code requiresCsrf} predicate reference, so they
 * cannot drift apart, and the cookie guard of the constructor.
 */
class SecurityConfigSharedMatcherTest extends BaseUnitTest {

    @Test
    void shouldExemptOnlyOneClickPostFromSessionRequirements() {
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/notifications/unsubscribe/token");
        post.setServletPath("/api/notifications/unsubscribe/token");
        assertThat(SecurityConfig.EMAIL_UNSUBSCRIBE_MATCHER.matches(post)).isTrue();
        for (String path : List.of(
                "/api/notifications/unsubscribe",
                "/api/notifications/unsubscribe/token/extra",
                "/api/user/notification-preferences")) {
            MockHttpServletRequest other = new MockHttpServletRequest("POST", path);
            other.setServletPath(path);
            assertThat(SecurityConfig.EMAIL_UNSUBSCRIBE_MATCHER.matches(other)).isFalse();
        }
        post.setMethod("GET");
        assertThat(SecurityConfig.EMAIL_UNSUBSCRIBE_MATCHER.matches(post)).isFalse();
    }

    @Test
    void shouldPermitOnlyPublishedActivityReads() {
        for (String path : List.of(
                "/api/public/workspaces/team/activity",
                "/api/public/workspaces/team/activity/people/developer",
                "/api/public/workspaces/team/activity/people/developer/work")) {
            for (String method : List.of("GET", "HEAD", "POST")) {
                var request = new MockHttpServletRequest(method, path);
                request.setServletPath(path);
                assertThat(PublicActivityReadRequestMatcher.MATCHER.matches(request))
                        .as("%s %s", method, path)
                        .isEqualTo(!method.equals("POST"));
            }
        }
        for (String path : List.of(
                "/api/public/workspaces/team/activity/private",
                "/api/public/workspaces/team/activity/people",
                "/api/public/workspaces/team/activity/people/developer/work/extra")) {
            var request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            assertThat(PublicActivityReadRequestMatcher.MATCHER.matches(request))
                    .as(path)
                    .isFalse();
        }
    }

    private static ObjectProvider<InstalledClientRegistry> noClients() {
        return new StaticListableBeanFactory().getBeanProvider(InstalledClientRegistry.class);
    }

    @Test
    void shouldMatchOnlyTheThreeInstalledClientPostsWhenDecidingPublicAccessAndCsrf() {
        for (String path : List.of("/api/auth/client/token", "/api/auth/client/refresh", "/api/auth/client/logout")) {
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
        for (String path :
                List.of("/api/auth/client/configuration", "/api/auth/client/other", "/api/auth/client/token/x")) {
            MockHttpServletRequest post = new MockHttpServletRequest("POST", path);
            post.setServletPath(path);
            assertThat(SecurityConfig.CLIENT_SESSION_MATCHER.matches(post))
                    .as(path)
                    .isFalse();
        }
        MockHttpServletRequest configuration = new MockHttpServletRequest("GET", "/api/auth/client/configuration");
        configuration.setServletPath("/api/auth/client/configuration");
        assertThat(SecurityConfig.CLIENT_CONFIGURATION_MATCHER.matches(configuration))
                .isTrue();
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
                        "HEPHAESTUS_AT"))
                .isNotNull();
    }

    @Test
    void csrfCookieName_dropsHostPrefixOnlyWhenInsecure() {
        assertThat(SecurityConfig.csrfCookieName(true)).isEqualTo("__Host-XSRF-TOKEN");
        assertThat(SecurityConfig.csrfCookieName(false)).isEqualTo("XSRF-TOKEN");
    }
}
