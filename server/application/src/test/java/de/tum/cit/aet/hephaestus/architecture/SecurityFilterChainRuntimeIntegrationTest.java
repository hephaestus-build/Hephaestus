package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitFilter;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class SecurityFilterChainRuntimeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private List<SecurityFilterChain> filterChains;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlers;

    @Test
    void shouldRejectEveryAnonymousHandlerOutsideTheExplicitAllowlist() {
        var anonymous = new AnonymousAuthenticationToken(
                "test", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        List<String> violations = new ArrayList<>();
        handlers.getHandlerMethods().forEach((mapping, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("de.tum.cit.aet.hephaestus")) return;
            var name = handler.getBeanType().getSimpleName() + "."
                    + handler.getMethod().getName();
            var methods = mapping.getMethodsCondition().getMethods();
            if (methods.isEmpty()) methods = Set.of(RequestMethod.GET, RequestMethod.POST);
            for (String pattern : mapping.getPatternValues()) {
                for (RequestMethod method : methods) {
                    if (method == RequestMethod.OPTIONS) continue;
                    String path = pattern.replaceAll("\\{[^}]+}", "anonymous-probe");
                    var request = new MockHttpServletRequest(method.name(), path);
                    request.setServletPath(path);
                    var chain = filterChains.stream()
                            .filter(candidate -> candidate.matches(request))
                            .findFirst()
                            .orElseThrow();
                    var authorization = chain.getFilters().stream()
                            .filter(AuthorizationFilter.class::isInstance)
                            .map(AuthorizationFilter.class::cast)
                            .findFirst()
                            .orElseThrow();
                    var result = authorization.getAuthorizationManager().authorize(() -> anonymous, request);
                    if ((result == null || result.isGranted()) && !AnonymousEndpointAllowlist.HANDLERS.contains(name)) {
                        violations.add(method + " " + pattern + " -> " + name);
                    }
                }
            }
        });
        assertThat(violations).isEmpty();
    }

    @Test
    void csrfFilterGuardsOnlyTheCookieAppChain() {
        assertThat(filterChains).isNotEmpty();
        // Only the app resource-server chain authenticates browser cookies. Worker/webhook and
        // OAuth login chains use their own authentication and must not require a CSRF cookie.
        for (SecurityFilterChain chain : filterChains) {
            boolean hasCsrf = chain.getFilters().stream().anyMatch(CsrfFilter.class::isInstance);
            boolean authenticatesUserTokens =
                    chain.getFilters().stream().anyMatch(BearerTokenAuthenticationFilter.class::isInstance);
            assertThat(hasCsrf)
                    .as("only the cookie-authenticated resource-server chain may carry a CsrfFilter: %s", chain)
                    .isEqualTo(authenticatesUserTokens);
        }
        // Guard against an accidental global CSRF disable: the cookie app chain must still enforce it.
        assertThat(filterChains)
                .as("the cookie app chain must enforce CSRF")
                .anyMatch(chain -> chain.getFilters().stream().anyMatch(CsrfFilter.class::isInstance));
    }

    @Test
    void authRateLimitFilterIsInstalledOnASecurityChain() {
        // All rate-limit coverage is the isolated filter unit test driving doFilter() directly, so a
        // regression removing addFilterBefore(authRateLimitFilter, AuthorizationFilter.class) — disabling
        // auth rate limiting entirely in prod — would otherwise be invisible. Assert it is actually wired.
        assertThat(filterChains)
                .as("at least one security chain must install AuthRateLimitFilter")
                .anyMatch(chain -> chain.getFilters().stream().anyMatch(AuthRateLimitFilter.class::isInstance));
    }

    // The proxy beans are gated on the job-execution capability (worker role + hephaestus.agent.enabled),
    // which this context leaves off, so the LLM proxy chain's filter order is asserted in
    // LlmProxyIntegrationTest.CrossChainSecurity#llmProxyChainHasJobTokenFilterBeforeUpaf instead.
}
