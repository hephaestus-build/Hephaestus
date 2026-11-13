package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Anonymous metrics depend on which listener a scrape arrived on. Two listeners on different addresses can hold the
 * same ephemeral port number, so each case here gives both listeners port 41000.
 */
@Tag("unit")
class ManagementListenerMatcherTest {

    private static final int SHARED_PORT = 41000;

    private static MockEnvironment separateListeners() {
        return new MockEnvironment()
                .withProperty("server.port", "0")
                .withProperty("server.address", "127.0.0.1")
                .withProperty("management.server.port", "0")
                .withProperty("management.server.address", "::1")
                .withProperty("local.server.port", String.valueOf(SHARED_PORT))
                .withProperty("local.management.port", String.valueOf(SHARED_PORT));
    }

    private static MockHttpServletRequest arrivingOn(String localAddress, int localPort) {
        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setLocalAddr(localAddress);
        request.setLocalPort(localPort);
        return request;
    }

    @Test
    void shouldMatchTheManagementListenerWhenTheApplicationListenerHasTheSamePortNumber() {
        RequestMatcher matcher = SecurityConfig.managementListener(separateListeners());

        assertThat(matcher.matches(arrivingOn("0:0:0:0:0:0:0:1", SHARED_PORT))).isTrue();
    }

    @Test
    void shouldNotMatchTheApplicationListenerWhenTheManagementListenerHasTheSamePortNumber() {
        RequestMatcher matcher = SecurityConfig.managementListener(separateListeners());

        assertThat(matcher.matches(arrivingOn("127.0.0.1", SHARED_PORT))).isFalse();
    }

    @Test
    void shouldMatchByPortWhenTheManagementListenerBindsEveryAddress() {
        var wildcard = new MockEnvironment()
                .withProperty("server.port", "0")
                .withProperty("management.server.port", "0")
                .withProperty("local.server.port", "41001")
                .withProperty("local.management.port", String.valueOf(SHARED_PORT));

        assertThat(SecurityConfig.managementListener(wildcard).matches(arrivingOn("10.0.0.7", SHARED_PORT)))
                .isTrue();
        assertThat(SecurityConfig.managementListener(wildcard).matches(arrivingOn("10.0.0.7", 41001)))
                .isFalse();
    }

    @Test
    void shouldMatchNothingWhenManagementSharesTheApplicationListener() {
        var shared = new MockEnvironment()
                .withProperty("server.port", "8080")
                .withProperty("local.server.port", "8080")
                .withProperty("local.management.port", "8080");

        assertThat(SecurityConfig.managementListener(shared).matches(arrivingOn("127.0.0.1", 8080)))
                .isFalse();
    }
}
