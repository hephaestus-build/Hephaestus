package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Pins the admin-facing callback URL — the value an operator copies into the upstream OAuth app — so it
 * carries the proxy-stripped API prefix exactly like the live {@code redirect_uri} does. Without this the
 * displayed URL silently drifts to the un-prefixed (SPA) path and every self-hosted-GitLab wiring fails.
 */
class LoginProviderAdminControllerCallbackTest extends BaseUnitTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void displayedCallback_carriesApiBasePath_behindAStrippingProxy() {
        String redirectUri = redirectUriFor("/api");
        assertThat(redirectUri).isEqualTo("https://hephaestus.example/api/login/oauth2/code/github");
    }

    @Test
    void displayedCallback_hasNoPrefix_whenServedAtRoot() {
        String redirectUri = redirectUriFor("");
        assertThat(redirectUri).isEqualTo("https://hephaestus.example/login/oauth2/code/github");
    }

    private static String redirectUriFor(String apiBasePath) {
        LoginProviderService service = mock(LoginProviderService.class);
        when(service.listAll()).thenReturn(List.of(provider()));
        var controller = new LoginProviderAdminController(service, AuthPropertiesFixture.withApiBasePath(apiBasePath));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("hephaestus.example");
        request.setServerPort(443);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        var body = controller.list().getBody();
        assertThat(body).isNotNull();
        return body.getFirst().redirectUri();
    }

    private static LoginProvider provider() {
        LoginProvider p = new LoginProvider();
        p.setRegistrationId("github");
        p.setType(LoginProvider.ProviderType.GITHUB);
        p.setDisplayName("GitHub");
        p.setBaseUrl("https://github.com");
        p.setScopes("read:user");
        p.setEnabled(true);
        return p;
    }
}
