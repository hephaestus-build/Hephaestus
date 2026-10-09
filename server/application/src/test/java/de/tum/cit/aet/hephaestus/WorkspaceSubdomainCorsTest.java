package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.config.CorsProperties;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientRegistry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceOriginPolicy;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSubdomainProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;

class WorkspaceSubdomainCorsTest extends BaseUnitTest {
    private static final String APEX = "https://hephaestus.build";

    private static CorsConfigurationSource source(boolean enabled) {
        var policy = new WorkspaceOriginPolicy(
                new WorkspaceSubdomainProperties(enabled, "hephaestus.build"), APEX, URI.create(APEX));
        var beans = new StaticListableBeanFactory();
        beans.addBean("workspaceOrigins", policy);
        var security = new SecurityConfig(
                new CorsProperties(List.of(APEX, "https://docs.hephaestus.build")),
                beans.getBeanProvider(InstalledClientRegistry.class),
                beans.getBeanProvider(WorkspaceOriginPolicy.class),
                new MockEnvironment(),
                false,
                false,
                true,
                AuthProperties.DEFAULT_COOKIE_NAME);
        return security.corsConfigurationSource();
    }

    private static MockHttpServletResponse preflight(CorsConfigurationSource source, String origin) throws Exception {
        var request = new MockHttpServletRequest("OPTIONS", "/auth/csrf");
        request.setScheme("https");
        request.setServerName("hephaestus.build");
        request.setServerPort(443);
        request.addHeader(HttpHeaders.ORIGIN, origin);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-XSRF-TOKEN, Content-Type");
        var response = new MockHttpServletResponse();
        new DefaultCorsProcessor().processRequest(source.getCorsConfiguration(request), request, response);
        return response;
    }

    @ParameterizedTest
    @ValueSource(strings = {"ls1intum", "unknown", "a", "1", "team-one"})
    void shouldAllowCredentialsWithoutRevealingWorkspaceExistence(String label) throws Exception {
        String origin = "https://" + label + ".hephaestus.build";
        var response = preflight(source(true), origin);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(origin);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .isEqualTo("true");
        assertThat(response.getHeaders(HttpHeaders.VARY)).contains(HttpHeaders.ORIGIN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://docs.hephaestus.build",
                "https://admin.hephaestus.build",
                "https://pr123.hephaestus.build",
                "https://postmaster.hephaestus.build",
                "https://xn--test.hephaestus.build",
                "https://a--b.hephaestus.build",
                "https://team--one.hephaestus.build",
                "https://-bad.hephaestus.build",
                "https://with_under.hephaestus.build",
                "https://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.hephaestus.build",
                "http://ls1intum.hephaestus.build",
                "https://evil-hephaestus.build",
                "https://ls1intum.hephaestus.build.evil.com",
                "https://nested.ls1intum.hephaestus.build",
                "https://ls1intum.hephaestus.build:444",
                "null",
                "https://ls1intum.hephaestus.build/",
                "https://ls1intum.hephaestus.build?x=y",
                "https://ls1intum.hephaestus.build#fragment",
                "https://user@ls1intum.hephaestus.build",
                "https://LS1INTUM.hephaestus.build",
                "https://ls1intum.hephaestus.build.",
                "https://bad-.hephaestus.build"
            })
    void shouldDenyOriginWhenItIsNotAnExactTrustedTenantOrigin(String origin) throws Exception {
        var response = preflight(source(true), origin);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void shouldRetainApexPolicyAndDenyTenantsWhenSwitchIsOff() throws Exception {
        var source = source(false);
        assertThat(preflight(source, APEX).getStatus()).isEqualTo(200);
        assertThat(preflight(source, "https://ls1intum.hephaestus.build").getStatus())
                .isEqualTo(403);
    }
}
