package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitFilter;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;

class SecurityFilterChainRuntimeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private List<SecurityFilterChain> filterChains;

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
