package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class ConsentGateInterceptorTest extends BaseUnitTest {

    @Test
    void shouldAllowOnlyExactBootstrapEndpoints() {
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("GET", "/user/consent"))
                .isTrue();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("PUT", "/user/consent"))
                .isTrue();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("POST", "/auth/logout"))
                .isTrue();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("DELETE", "/user"))
                .isTrue();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("GET", "/auth/client/configuration"))
                .isTrue();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("GET", "/auth/dev-login/client"))
                .isTrue();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("POST", "/auth/dev-login"))
                .isTrue();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("POST", "/auth/client/token"))
                .isFalse();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("GET", "/workspaces"))
                .isFalse();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("POST", "/auth/dev-login/client"))
                .isFalse();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("PUT", "/user"))
                .isFalse();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("PUT", "/user/consent/research"))
                .isFalse();
        assertThat(ConsentGateInterceptor.isAllowedBeforeConsent("GET", "/user/consent/../settings"))
                .isFalse();
    }
}
