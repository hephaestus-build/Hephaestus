package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.springframework.security.oauth2.core.authorization.OAuth2AuthorizationManagers.hasScope;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.core.web.PayloadSizeFilter;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.function.Predicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * The sandbox gateway's three chains. The first two are ordered ahead of every application chain because they claim a
 * request by the connector it arrived on rather than by its path; the third matches by path and only has to precede
 * the chains that would otherwise serve {@code /internal/llm/**}:
 *
 * <ol>
 *   <li>the sandbox capabilities on the gateway connector, authenticated by a sandbox credential
 *       ({@link JobTokenAuthenticationProvider}) and rate-limited per caller. A review job reaches its runtime, its
 *       review endpoints and the model; a Heph turn reaches the model and its own turn endpoints; a Heph host's session
 *       reaches only its own runtime discovery, workspace and command channel. The Heph host's turn bookkeeping (its
 *       context, partial answer and end) is exempt from the rate limit: the host sends a partial answer several times
 *       a second, and the model-driven calls of the same turn stay limited;
 *   <li>everything else on the gateway connector, denied and answered {@code 404} so the connector admits to no route
 *       a sandbox is not meant to call;
 *   <li>the capability paths on every other connector, denied the same way, so they exist only where sandboxes reach
 *       them.
 * </ol>
 *
 * <p>Gated on the worker role rather than the practice-review feature flag, because disabling practice reviews must not
 * break Heph turns.
 */
@Configuration
@ConditionalOnWorkerRole
class LlmProxySecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain llmProxyFilterChain(
            HttpSecurity http,
            SandboxGatewayProperties gatewayProperties,
            SandboxGatewayConnector gateway,
            ReviewJobRepository agentJobRepository,
            MentorTurnRepository mentorTurnRepository,
            SandboxGatewaySessions gatewaySessions,
            WorkerJwtVerifier jwtVerifier,
            BucketResolver bucketResolver,
            ProxyAccounting accounting,
            ObjectMapper objectMapper,
            WorkerProperties workerProperties)
            throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher practiceScriptCalls = paths.matcher(HttpMethod.POST, "/internal/llm/practice-script/{slot}/**");
        RequestMatcher models = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/internal/llm/chat/completions"),
                paths.matcher(HttpMethod.POST, "/internal/llm/responses"));
        RequestMatcher reviewCalls = new OrRequestMatcher(
                practiceScriptCalls,
                paths.matcher(HttpMethod.POST, "/internal/llm/admit-results"),
                paths.matcher(HttpMethod.POST, "/internal/llm/public-feedback-history"),
                paths.matcher(HttpMethod.POST, "/internal/llm/prepare-feedback"));
        RequestMatcher modelCalls = new OrRequestMatcher(models, reviewCalls);
        RequestMatcher runtimeReads = new OrRequestMatcher(
                paths.matcher(HttpMethod.GET, "/internal/llm/runtime/{id}"),
                paths.matcher(HttpMethod.GET, "/internal/llm/runtime/{id}/workspace"));
        RequestMatcher resultUpload = paths.matcher(HttpMethod.POST, "/internal/llm/runtime/{id}/result");
        RequestMatcher hostCommands = paths.matcher(HttpMethod.GET, "/internal/llm/runtime/{id}/commands");
        RequestMatcher turnTools = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/internal/llm/turn/{id}/link-result"),
                paths.matcher(HttpMethod.POST, "/internal/llm/turn/{id}/link-problem"),
                paths.matcher(HttpMethod.POST, "/internal/llm/turn/{id}/show-feedback"),
                paths.matcher(HttpMethod.POST, "/internal/llm/turn/{id}/search"),
                paths.matcher(HttpMethod.POST, "/internal/llm/turn/{id}/sandbox/read"),
                paths.matcher(HttpMethod.POST, "/internal/llm/turn/{id}/sandbox/exec"),
                paths.matcher(HttpMethod.POST, "/internal/llm/turn/{id}/sandbox/load"));
        RequestMatcher turnBookkeeping = new OrRequestMatcher(
                paths.matcher(HttpMethod.GET, "/internal/llm/turn/{id}/context"),
                paths.matcher(HttpMethod.PUT, "/internal/llm/turn/{id}/partial"),
                paths.matcher(HttpMethod.POST, "/internal/llm/turn/{id}/end"));
        RequestMatcher turnEndpoints = new OrRequestMatcher(turnTools, turnBookkeeping);
        RequestMatcher bodies = new OrRequestMatcher(modelCalls, turnEndpoints);
        RequestMatcher capabilities =
                new OrRequestMatcher(modelCalls, runtimeReads, resultUpload, hostCommands, turnEndpoints);
        var limit = new AuthRateLimitProperties.Limit(gatewayProperties.requestsPerMinute(), Duration.ofMinutes(1));

        http.securityMatcher(new AndRequestMatcher(onGatewayConnector(gateway), capabilities))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, exception) -> response.setStatus(HttpStatus.NOT_FOUND.value())))
                .authorizeHttpRequests(auth -> auth.requestMatchers(hostCommands)
                        .access(caller(identity -> identity.hostSession() != null))
                        .requestMatchers(runtimeReads)
                        .access(AuthorizationManagers.anyOf(
                                caller(identity -> identity.hostSession() != null),
                                AuthorizationManagers.allOf(
                                        hasScope(WorkerJwtIssuer.LLM_PROXY_SCOPE),
                                        caller(identity -> identity.job() != null))))
                        .requestMatchers(models)
                        .access(AuthorizationManagers.allOf(
                                hasScope(WorkerJwtIssuer.LLM_PROXY_SCOPE),
                                caller(identity -> identity.job() != null || identity.turn() != null)))
                        .requestMatchers(turnEndpoints)
                        .access(AuthorizationManagers.allOf(
                                hasScope(WorkerJwtIssuer.LLM_PROXY_SCOPE), caller(identity -> identity.turn() != null)))
                        .anyRequest()
                        .access(AuthorizationManagers.allOf(
                                hasScope(WorkerJwtIssuer.LLM_PROXY_SCOPE), caller(identity -> identity.job() != null))))
                .addFilterBefore(
                        new JobTokenAuthenticationFilter(
                                agentJobRepository,
                                mentorTurnRepository,
                                gatewaySessions,
                                jwtVerifier,
                                workerProperties.resolvedWorkerId()),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        bounded(gatewayProperties.maxRequestBytes(), bodies), JobTokenAuthenticationFilter.class)
                .addFilterBefore(
                        bounded(SandboxOutputArchive.MAX_OUTPUT_BYTES, resultUpload),
                        JobTokenAuthenticationFilter.class)
                .addFilterAfter(
                        new SandboxGatewayRateLimitFilter(
                                limit, bucketResolver, objectMapper, accounting, turnBookkeeping),
                        AuthorizationFilter.class);

        return http.build();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain hideNonGatewayCapabilities(HttpSecurity http, SandboxGatewayConnector gateway)
            throws Exception {
        return hidden(http, onGatewayConnector(gateway));
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 3)
    SecurityFilterChain blockLlmProxyOnOtherConnectors(HttpSecurity http) throws Exception {
        return hidden(http, PathPatternRequestMatcher.withDefaults().matcher("/internal/llm/**"));
    }

    /** Admits an authenticated sandbox caller of one kind. */
    private static AuthorizationManager<RequestAuthorizationContext> caller(Predicate<ProxyIdentity> kind) {
        return (authentication, context) -> new AuthorizationDecision(
                authentication.get().getPrincipal() instanceof ProxyIdentity identity && kind.test(identity));
    }

    /** The body bound over the requests one matcher selects, so each capability states its own. */
    private static PayloadSizeFilter bounded(long maxBytes, RequestMatcher requests) {
        return new PayloadSizeFilter(maxBytes) {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !requests.matches(request);
            }
        };
    }

    /** Written once so the two connector-matched chains provably claim the same requests. */
    private static RequestMatcher onGatewayConnector(SandboxGatewayConnector gateway) {
        return request -> request.getLocalPort() == gateway.port();
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
