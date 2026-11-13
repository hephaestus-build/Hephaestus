package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.springframework.security.oauth2.core.authorization.OAuth2AuthorizationManagers.hasScope;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.RateLimitResponse;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.web.PayloadSizeFilter;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnProgress;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * The sandbox gateway's three chains. The first two are ordered ahead of every application chain because they claim a
 * request by the connector it arrived on rather than by its path; the third matches by path and only has to precede
 * the chains that would otherwise serve {@code /internal/llm/**}:
 *
 * <ol>
 *   <li>the sandbox capabilities on the gateway connector, authenticated by an attempt's credential, or by a
 *       sandbox's token with the claim of a run that it holds ({@link JobTokenAuthenticationProvider}), rate-limited per source address before any credential is read and per
 *       caller after it. A review job reaches its runtime, its
 *       review endpoints and the model; a conversation sandbox reaches the model through its turn's run claim.
 *       The agent routes serve the sandbox's own context, partial answer and proposals, authenticated with its
 *       sandbox token and fenced on the run and epoch;
 *   <li>everything else on the gateway connector, denied and answered {@code 404} so the connector admits to no route
 *       a sandbox is not meant to call;
 *   <li>the capability paths on every other connector, denied the same way, so they exist only where sandboxes reach
 *       them.
 * </ol>
 *
 * <p>Gated on the agent runtime rather than the practice-review feature flag, because disabling practice reviews must not
 * break Heph turns.
 */
@Configuration
@ConditionalOnAgentRuntime
class LlmProxySecurityConfig {

    /** The routes of a sandbox's own token: its pull, and later its run's context, lease and proposals. */
    private static final RequestMatcher AGENT_ROUTES =
            PathPatternRequestMatcher.withDefaults().matcher("/internal/agent/**");

    /** A refused caller asks again this often; each attempt costs one request of its rate limit. */
    private static final long BODY_BUDGET_RETRY_SECONDS = 5;

    @Bean
    SandboxGatewayBodyBudget sandboxGatewayBodyBudget() {
        return SandboxGatewayBodyBudget.ofHeap();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain llmProxyFilterChain(
            HttpSecurity http,
            SandboxGatewayProperties gatewayProperties,
            SandboxGatewayConnector gateway,
            ReviewJobRepository agentJobRepository,
            AgentRuns agentRuns,
            AttemptTokenVerifier jwtVerifier,
            ObjectProvider<AgentSandboxes> sandboxes,
            List<SandboxRunKind> sandboxRunKinds,
            BucketResolver bucketResolver,
            SandboxGatewayBodyBudget bodyBudget,
            ProxyAccounting accounting,
            ObjectMapper objectMapper)
            throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher models = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/internal/llm/chat/completions"),
                paths.matcher(HttpMethod.POST, "/internal/llm/responses"),
                paths.matcher(HttpMethod.POST, "/internal/llm/v1/messages"));
        RequestMatcher reviewCalls = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/internal/llm/admit-results"),
                paths.matcher(HttpMethod.POST, "/internal/llm/stage-result"),
                paths.matcher(HttpMethod.POST, "/internal/llm/start-practice"));
        RequestMatcher modelCalls = new OrRequestMatcher(models, reviewCalls);
        RequestMatcher runtimeReads = new OrRequestMatcher(
                paths.matcher(HttpMethod.GET, "/internal/llm/runtime/{id}"),
                paths.matcher(HttpMethod.GET, "/internal/llm/runtime/{id}/workspace"));
        RequestMatcher resultUpload = paths.matcher(HttpMethod.POST, "/internal/llm/runtime/{id}/result");
        RequestMatcher bodies = modelCalls;
        RequestMatcher capabilities = new OrRequestMatcher(modelCalls, runtimeReads, resultUpload);
        var limit = new AuthRateLimitProperties.Limit(gatewayProperties.requestsPerMinute(), Duration.ofMinutes(1));
        var sourceLimit =
                new AuthRateLimitProperties.Limit(gatewayProperties.sourceRequestsPerMinute(), Duration.ofMinutes(1));

        http.securityMatcher(new AndRequestMatcher(onGatewayConnector(gateway), capabilities))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                                (request, response, exception) -> response.setStatus(HttpStatus.NOT_FOUND.value()))
                        .accessDeniedHandler((request, response, exception) -> {
                            accounting.gatewayRefused(SandboxGatewayRefusal.FORBIDDEN);
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                        }))
                .authorizeHttpRequests(auth -> auth.requestMatchers(runtimeReads)
                        .access(AuthorizationManagers.allOf(
                                hasScope(AttemptTokenIssuer.LLM_PROXY_SCOPE),
                                caller(identity -> identity.job() != null)))
                        .requestMatchers(models)
                        .access(AuthorizationManagers.allOf(
                                hasScope(AttemptTokenIssuer.LLM_PROXY_SCOPE),
                                caller(identity -> identity.job() != null || identity.turn() != null)))
                        .anyRequest()
                        .access(AuthorizationManagers.allOf(
                                hasScope(AttemptTokenIssuer.LLM_PROXY_SCOPE),
                                caller(identity -> identity.job() != null))))
                .addFilterBefore(
                        new JobTokenAuthenticationFilter(
                                agentJobRepository,
                                agentRuns,
                                jwtVerifier,
                                sandboxes.getIfAvailable(),
                                SandboxRunKind.byKind(sandboxRunKinds),
                                accounting),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new SandboxGatewaySourceLimitFilter(sourceLimit, objectMapper, accounting),
                        JobTokenAuthenticationFilter.class)
                .addFilterBefore(
                        bounded(gatewayProperties.maxRequestBytes(), bodies, bodyBudget, objectMapper, accounting),
                        JobTokenAuthenticationFilter.class)
                .addFilterBefore(
                        bounded(
                                SandboxOutputArchive.MAX_OUTPUT_BYTES,
                                resultUpload,
                                bodyBudget,
                                objectMapper,
                                accounting),
                        JobTokenAuthenticationFilter.class)
                .addFilterAfter(
                        new SandboxGatewayRateLimitFilter(
                                limit, bucketResolver, objectMapper, accounting, request -> false),
                        AuthorizationFilter.class);

        return http.build();
    }

    /**
     * The agent routes of a sandbox on the gateway connector, authenticated by its sandbox token and rate-limited per
     * source address before the token is read. Its paths and the first chain's are disjoint, so the two share the
     * first place; both precede the chain that hides the rest of the connector.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain agentGatewayFilterChain(
            HttpSecurity http,
            SandboxGatewayProperties gatewayProperties,
            SandboxGatewayConnector gateway,
            AttemptTokenVerifier jwtVerifier,
            ObjectProvider<AgentSandboxes> sandboxes,
            SandboxGatewayBodyBudget bodyBudget,
            ProxyAccounting accounting,
            ObjectMapper objectMapper)
            throws Exception {
        // A review sandbox's result, bounded as a review Job's result is on the capability chain.
        RequestMatcher reviewResult = PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.POST, "/internal/agent/runs/{runId}/runtime/result");
        RequestMatcher turnPartial = PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.POST, "/internal/agent/runs/{runId}/turn/partial");
        RequestMatcher turnBodies = new AndRequestMatcher(
                PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/internal/agent/runs/{runId}/turn/**"),
                new NegatedRequestMatcher(turnPartial));
        var sourceLimit =
                new AuthRateLimitProperties.Limit(gatewayProperties.sourceRequestsPerMinute(), Duration.ofMinutes(1));
        var authentication = new SandboxTokenAuthenticationFilter(jwtVerifier, sandboxes.getIfAvailable(), accounting);
        http.securityMatcher(new AndRequestMatcher(onGatewayConnector(gateway), AGENT_ROUTES))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                                (request, response, exception) -> response.setStatus(HttpStatus.NOT_FOUND.value()))
                        .accessDeniedHandler((request, response, exception) -> {
                            accounting.gatewayRefused(SandboxGatewayRefusal.FORBIDDEN);
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                        }))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(authentication, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new SandboxGatewaySourceLimitFilter(sourceLimit, objectMapper, accounting),
                        SandboxTokenAuthenticationFilter.class)
                .addFilterBefore(
                        bounded(
                                SandboxOutputArchive.MAX_OUTPUT_BYTES,
                                reviewResult,
                                bodyBudget,
                                objectMapper,
                                accounting),
                        SandboxTokenAuthenticationFilter.class)
                .addFilterBefore(
                        bounded(gatewayProperties.maxRequestBytes(), turnBodies, bodyBudget, objectMapper, accounting),
                        SandboxTokenAuthenticationFilter.class)
                .addFilterBefore(
                        bounded(MentorTurnProgress.MAX_UPDATE_BYTES, turnPartial, bodyBudget, objectMapper, accounting),
                        SandboxTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain hideNonGatewayCapabilities(
            HttpSecurity http, SandboxGatewayConnector gateway, ProxyAccounting accounting) throws Exception {
        // An exception that left the first chain arrives here as an error dispatch: a failure, not a caller's probe.
        return hidden(http, onGatewayConnector(gateway), request -> {
            if (request.getDispatcherType() != DispatcherType.ERROR)
                accounting.gatewayRefused(SandboxGatewayRefusal.UNKNOWN_ROUTE);
        });
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 3)
    SecurityFilterChain blockLlmProxyOnOtherConnectors(HttpSecurity http) throws Exception {
        return hidden(
                http,
                new OrRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher("/internal/llm/**"), AGENT_ROUTES),
                request -> {});
    }

    /** Admits an authenticated sandbox caller of one kind. */
    private static AuthorizationManager<RequestAuthorizationContext> caller(Predicate<ProxyIdentity> kind) {
        return (authentication, context) -> new AuthorizationDecision(
                authentication.get().getPrincipal() instanceof ProxyIdentity identity && kind.test(identity));
    }

    /** The body bound over the requests one matcher selects, so each capability states its own. */
    private static PayloadSizeFilter bounded(
            long maxBytes,
            RequestMatcher requests,
            SandboxGatewayBodyBudget budget,
            ObjectMapper objectMapper,
            ProxyAccounting accounting) {
        return new PayloadSizeFilter(maxBytes) {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !requests.matches(request);
            }

            @Override
            protected void accepted(
                    HttpServletRequest request, HttpServletResponse response, FilterChain chain, long contentLength)
                    throws ServletException, IOException {
                if (!budget.tryHold(contentLength)) {
                    accounting.gatewayRefused(SandboxGatewayRefusal.BODY_BUDGET);
                    // The refusal that a model client waits out; a limit of the gateway is no provider's answer.
                    response.setHeader(LlmProxyService.REFUSAL_HEADER, ProxyRefusal.CALLS_IN_FLIGHT.name());
                    RateLimitResponse.writeTooManyRequests(request, response, BODY_BUDGET_RETRY_SECONDS, objectMapper);
                    return;
                }
                try {
                    chain.doFilter(request, response);
                } finally {
                    budget.release(contentLength);
                }
            }

            @Override
            protected void refused(HttpServletRequest request, String reason) {
                accounting.gatewayRefused(SandboxGatewayRefusal.OVERSIZED);
            }
        };
    }

    /** Written once so the two connector-matched chains provably claim the same requests. */
    private static RequestMatcher onGatewayConnector(SandboxGatewayConnector gateway) {
        return request -> request.getLocalPort() == gateway.port();
    }

    /** Denies everything it matches and reports nothing but {@code 404} while doing so. */
    private static SecurityFilterChain hidden(
            HttpSecurity http, RequestMatcher matcher, Consumer<HttpServletRequest> onRefusal) throws Exception {
        http.securityMatcher(matcher)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                            onRefusal.accept(request);
                            response.setStatus(HttpStatus.NOT_FOUND.value());
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            onRefusal.accept(request);
                            response.setStatus(HttpStatus.NOT_FOUND.value());
                        }))
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
        return http.build();
    }
}
