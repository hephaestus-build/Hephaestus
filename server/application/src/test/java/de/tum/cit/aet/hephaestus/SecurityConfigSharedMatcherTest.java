package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.config.CorsProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
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

    @Test
    void devTriggerMatcherMatchesDevPathsOnly() {
        MockHttpServletRequest dev = new MockHttpServletRequest("POST", "/api/dev/trigger-review");
        MockHttpServletRequest notDev = new MockHttpServletRequest("POST", "/user/something");

        assertThat(SecurityConfig.DEV_TRIGGER_MATCHER.matches(dev)).isTrue();
        assertThat(SecurityConfig.DEV_TRIGGER_MATCHER.matches(notDev)).isFalse();
    }

    @Test
    void cookieSecureFalseUnderProd_failsClosedAtConstruction() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new SecurityConfig(
                        new CorsProperties(List.of("https://example.com")), prod, false, false, false, "HEPHAESTUS_AT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookie-secure");
    }

    @Test
    void cookieSecureFalseOutsideProd_constructs() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev", "e2e");
        assertThat(new SecurityConfig(
                        new CorsProperties(List.of("https://example.com")), dev, false, false, false, "HEPHAESTUS_AT"))
                .isNotNull();
    }

    @Test
    void csrfCookieName_dropsHostPrefixOnlyWhenInsecure() {
        assertThat(SecurityConfig.csrfCookieName(true)).isEqualTo("__Host-XSRF-TOKEN");
        assertThat(SecurityConfig.csrfCookieName(false)).isEqualTo("XSRF-TOKEN");
    }
}
