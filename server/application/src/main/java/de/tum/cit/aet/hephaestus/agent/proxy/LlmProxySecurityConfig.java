package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.core.web.PayloadSizeFilter;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * The sandbox gateway's three chains. The first two are ordered ahead of every application chain
 * because they claim a request by the connector it arrived on rather than by its path; the third
 * matches by path and only has to precede the chains that would otherwise serve
 * {@code /internal/llm/**}:
 *
 * <ol>
 *   <li>the sandbox capabilities on the gateway connector, authenticated by a proxy-scoped bearer
 *       credential and rate-limited per authenticated principal;
 *   <li>everything else on the gateway connector, denied and answered {@code 404} so the connector
 *       admits to no route a sandbox is not meant to call;
 *   <li>the capability paths on every other connector, denied the same way, so they exist only where
 *       sandboxes reach them.
 * </ol>
 *
 * <p>Gated on the worker/sandbox capability rather than the practice-job feature flag, because
 * disabling practice reviews must not break mentor turns.
 */
@Configuration
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
class LlmProxySecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain llmProxyFilterChain(
            HttpSecurity http,
            SandboxGatewayProperties gatewayProperties,
            AgentJobRepository agentJobRepository,
            WorkerJwtVerifier jwtVerifier,
            MentorProxyCredentialRegistry mentorRegistry,
            BucketResolver bucketResolver,
            ProxyAccounting accounting,
            ObjectMapper objectMapper,
            WorkerProperties workerProperties)
            throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher capabilities = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/internal/llm/chat/completions"),
                paths.matcher(HttpMethod.POST, "/internal/llm/responses"),
                paths.matcher(HttpMethod.POST, "/internal/llm/admit-observations"),
                paths.matcher(HttpMethod.GET, "/internal/llm/runtime/{id}"),
                paths.matcher(HttpMethod.GET, "/internal/llm/runtime/{id}/workspace"),
                paths.matcher(HttpMethod.GET, "/internal/llm/runtime/{id}/frames"),
                paths.matcher(HttpMethod.POST, "/internal/llm/runtime/{id}/result"));
        var limit = new AuthRateLimitProperties.Limit(gatewayProperties.requestsPerMinute(), Duration.ofMinutes(1));

        http.securityMatcher(new AndRequestMatcher(onGatewayConnector(gatewayProperties), capabilities))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, exception) -> response.setStatus(HttpStatus.NOT_FOUND.value())))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(
                        new JobTokenAuthenticationFilter(
                                agentJobRepository,
                                jwtVerifier,
                                mentorRegistry,
                                objectMapper,
                                workerProperties.resolvedWorkerId()),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new PayloadSizeFilter(gatewayProperties.maxRequestBytes()) {
                            @Override
                            protected boolean shouldNotFilter(jakarta.servlet.http.HttpServletRequest request) {
                                return request.getMethod().equals("GET")
                                        || request.getRequestURI().matches("/internal/llm/runtime/[^/]+/result");
                            }
                        },
                        JobTokenAuthenticationFilter.class)
                .addFilterAfter(
                        new SandboxGatewayRateLimitFilter(limit, bucketResolver, objectMapper, accounting),
                        JobTokenAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain hideNonGatewayCapabilities(HttpSecurity http, SandboxGatewayProperties gatewayProperties)
            throws Exception {
        return hidden(http, onGatewayConnector(gatewayProperties));
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 3)
    SecurityFilterChain blockLlmProxyOnOtherConnectors(HttpSecurity http) throws Exception {
        return hidden(http, PathPatternRequestMatcher.withDefaults().matcher("/internal/llm/**"));
    }

    /** Written once so the two connector-matched chains provably claim the same requests. */
    private static RequestMatcher onGatewayConnector(SandboxGatewayProperties gatewayProperties) {
        return request -> request.getLocalPort() == gatewayProperties.port();
    }

    /** Denies everything it matches and reports nothing but {@code 404} while doing so. */
    private static SecurityFilterChain hidden(HttpSecurity http, RequestMatcher matcher) throws Exception {
        http.securityMatcher(matcher)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                                (request, response, exception) -> response.setStatus(HttpStatus.NOT_FOUND.value()))
                        .accessDeniedHandler(
                                (request, response, exception) -> response.setStatus(HttpStatus.NOT_FOUND.value())))
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
        return http.build();
    }
}
