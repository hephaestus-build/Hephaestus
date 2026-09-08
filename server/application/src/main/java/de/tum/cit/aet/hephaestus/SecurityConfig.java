package de.tum.cit.aet.hephaestus;

import de.tum.cit.aet.hephaestus.config.CorsProperties;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitFilter;
import de.tum.cit.aet.hephaestus.core.security.ImpersonationGuard;
import de.tum.cit.aet.hephaestus.core.security.SecurityHeaders;
import de.tum.cit.aet.hephaestus.core.security.StaleAuthCookieFilter;
import de.tum.cit.aet.hephaestus.feature.FeatureFlag;
import de.tum.cit.aet.hephaestus.observability.ReplicaIdentityFilter;
import de.tum.cit.aet.hephaestus.observability.RequestCorrelationFilter;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final CorsProperties corsProperties;
    private final boolean devTriggerEnabled;
    private final boolean devLoginEnabled;
    private final boolean cookieSecure;
    private final String authCookieName;

    public SecurityConfig(
            CorsProperties corsProperties,
            Environment environment,
            @Value("${hephaestus.dev.trigger-enabled:false}") boolean devTriggerEnabled,
            @Value("${hephaestus.auth.dev-login-enabled:false}") boolean devLoginEnabled,
            @Value("${hephaestus.auth.cookie-secure:true}") boolean cookieSecure,
            @Value("${hephaestus.auth.cookie-name:" + AuthProperties.DEFAULT_COOKIE_NAME + "}") String authCookieName) {
        // Fail-closed: insecure cookies (Secure off, __Host- prefix dropped) are a local-http-E2E-only
        // affordance and must be impossible in production.
        if (!cookieSecure && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "hephaestus.auth.cookie-secure must NOT be false under the 'prod' profile (fail-closed).");
        }
        this.corsProperties = corsProperties;
        this.devTriggerEnabled = devTriggerEnabled;
        this.devLoginEnabled = devLoginEnabled;
        this.cookieSecure = cookieSecure;
        this.authCookieName = authCookieName;
    }

    /** CSRF cookie name: {@code __Host-}-prefixed in production; the prefix is dropped for local http E2E. */
    static String csrfCookieName(boolean cookieSecure) {
        return cookieSecure ? "__Host-XSRF-TOKEN" : "XSRF-TOKEN";
    }

    interface AuthoritiesConverter extends Converter<Map<String, Object>, Collection<GrantedAuthority>> {}

    @SuppressWarnings("unchecked")
    @Bean
    AuthoritiesConverter rolesAuthoritiesConverter() {
        return claims -> {
            // Flat `roles` claim on the Hephaestus-issued JWT (ADR 0017). The role strings
            // ("admin", "mentor_access", …) map 1:1 to granted authorities consumed by @PreAuthorize.
            final var roles = Optional.ofNullable((List<String>) claims.get("roles"));
            Stream<GrantedAuthority> granted = roles.map(List::stream)
                    .orElse(Stream.empty())
                    .map(SimpleGrantedAuthority::new)
                    .map(GrantedAuthority.class::cast);
            return Stream.concat(granted, signInFactor(claims.get("auth_time")).stream())
                    .toList();
        };
    }

    /**
     * The session's {@code auth_time} as Spring Security's authorization-code factor, so a freshness
     * requirement can be declared with the framework's own {@code requireFactor(…).validDuration(…)}
     * rather than compared by hand. GitHub and GitLab authorization-code logins are the only factor this
     * instance has; a token minted without {@code auth_time} carries no factor and is treated as stale.
     */
    private static Optional<GrantedAuthority> signInFactor(@Nullable Object authTime) {
        Instant issuedAt =
                switch (authTime) {
                    case Instant instant -> instant;
                    case Number epochSeconds -> Instant.ofEpochSecond(epochSeconds.longValue());
                    case null, default -> null;
                };
        return Optional.ofNullable(issuedAt)
                .map(at -> FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
                        .issuedAt(at)
                        .build());
    }

    @Bean
    JwtAuthenticationConverter authenticationConverter(
            Converter<Map<String, Object>, Collection<GrantedAuthority>> authoritiesConverter) {
        var authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
            return authoritiesConverter.convert(jwt.getClaims());
        });
        return authenticationConverter;
    }

    /**
     * Dedicated filter chain for the worker control channel (POST exchange + WSS upgrade) and
     * webhook ingress + actuator probes. These paths carry their own auth — worker JWT signed
     * by {@link de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerKeyRing}, HMAC/token at
     * the controller layer for webhooks, or no auth at all for health/info — and must NOT be
     * processed by the user-facing OAuth2 resource server. Running BearerTokenAuthenticationFilter
     * on the worker JWT runs it through the resource-server's ES256 decoder, which either fails
     * (signing key unavailable) or rejects (different signer), masking the real auth.
     *
     * <p>Always present, so a dev / worker-only pod can
     * boot without the resource-server JWT decoder configured.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    SecurityFilterChain workerHubSecurityFilterChain(HttpSecurity http) throws Exception {
        // Only paths whose auth lives at the controller / handshake layer go on this chain.
        // Other actuator endpoints (metrics, prometheus, loggers, heapdump, …) must NOT be
        // matched here — they fall through to the OAuth2 chain.
        http.securityMatcher(
                        "/api/workers/**",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/webhooks/github",
                        "/webhooks/gitlab",
                        "/webhooks/slack",
                        "/webhooks/slack/interactivity",
                        "/webhooks/outline",
                        "/oauth/callback/**")
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Application security chain (fallback after {@link #workerHubSecurityFilterChain}).
     *
     * <p>The {@link JwtDecoder} is resolved via {@link ObjectProvider} when this bean is built, so
     * it sees the decoder even when it comes from Spring Boot's OAuth2 autoconfig (registered after
     * user config). {@code @ConditionalOnBean(JwtDecoder)} can't be used here: it evaluates before
     * the autoconfig decoder is registered and would drop the server role into deny-all.
     *
     * <p>Decoder present (the native-auth {@link de.tum.cit.aet.hephaestus.core.auth.jwt.RevocationAwareJwtDecoder},
     * ADR 0017) → wire the OAuth2 resource server with the full native-auth rules (security headers,
     * auth rate limiting, the public auth-discovery permit list). Absent (worker-only pod that excludes
     * the OAuth2 autoconfig, or the {@code specs} profile that boots without a decoder) → deny everything.
     * The {@code hephaestus.dev.trigger-enabled} carve-out applies in both modes.
     */
    @Bean
    SecurityFilterChain appSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<JwtDecoder> jwtDecoderProvider,
            ObjectProvider<BearerTokenResolver> bearerTokenResolverProvider,
            ObjectProvider<StaleAuthCookieFilter> staleAuthCookieFilterProvider,
            Converter<Jwt, AbstractAuthenticationToken> authenticationConverter,
            ObjectProvider<AuthRateLimitFilter> authRateLimitFilterProvider,
            tools.jackson.databind.ObjectMapper objectMapper)
            throws Exception {
        http.sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()));

        JwtDecoder jwtDecoder = jwtDecoderProvider.getIfAvailable();
        if (jwtDecoder == null) {
            // Lockdown / specs boot: no user cookie sessions exist, so CSRF is moot — disable it to
            // keep the no-decoder pod and spec generation simple.
            http.csrf(csrf -> csrf.disable());
            http.authorizeHttpRequests(requests -> {
                requests.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                // The specs profile needs these endpoints without a JwtDecoder.
                requests.requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll();
                if (devTriggerEnabled) {
                    requests.requestMatchers("/api/dev/**").permitAll();
                }
                requests.anyRequest().denyAll();
            });
            // Same security headers as the resource-server chain — a no-decoder pod still serves
            // OpenAPI / OPTIONS / denied responses, so HSTS/CSP/COOP/etc. should apply here too.
            SecurityHeaders.apply(http);
            return http.build();
        }

        // Resolve the JWT from the SPA's __Host-HEPHAESTUS_AT cookie first, falling back to the
        // Authorization: Bearer header. The framework default reads ONLY the header, so without this
        // the cookie-authenticated SPA (credentials:"include", no Authorization header) is 401 on
        // every request. The resolver bean is built in the auth module (AuthJwtConfig) so this root
        // config depends only on the framework BearerTokenResolver interface. See ADR 0017.
        BearerTokenResolver bearerTokenResolver = bearerTokenResolverProvider.getIfAvailable();
        http.oauth2ResourceServer(resourceServer -> {
            if (bearerTokenResolver != null) {
                resourceServer.bearerTokenResolver(bearerTokenResolver);
            }
            resourceServer.jwt(jwtConfigurer -> {
                jwtConfigurer.decoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter);
            });
        });

        StaleAuthCookieFilter staleAuthCookieFilter = staleAuthCookieFilterProvider.getIfAvailable();
        if (staleAuthCookieFilter != null) {
            http.addFilterBefore(staleAuthCookieFilter, BearerTokenAuthenticationFilter.class);
        }

        http.csrf(csrf -> csrf.spa()
                .csrfTokenRepository(csrfTokenRepository())
                // A JWT request validates an existing session; it is not a new sign-in. Resetting
                // the CSRF cookie on every authentication races concurrent requests and other tabs.
                .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                    @Override
                    public <O extends CsrfFilter> O postProcess(O filter) {
                        // Resource-server initialization exempts all resolved tokens, including cookies.
                        // Set the matcher after initialization to retain CSRF checks for cookie auth.
                        filter.setRequireCsrfProtectionMatcher(SecurityConfig.this::requiresCsrf);
                        return filter;
                    }
                }));
        // Security headers (HSTS, CSP (enforced), COOP, COEP, Referrer-Policy,
        // X-Content-Type-Options) for the user-facing resource-server chain.
        SecurityHeaders.apply(http);

        // Register after authentication so account-scoped buckets can use the principal.
        AuthRateLimitFilter authRateLimitFilter = authRateLimitFilterProvider.getIfAvailable();
        if (authRateLimitFilter != null) {
            http.addFilterBefore(authRateLimitFilter, AuthorizationFilter.class);
        }

        // Read-only-by-default enforcement for impersonation sessions (JWT carries an `act`
        // claim). Registered AFTER the AuthorizationFilter so the SecurityContext already holds
        // the validated JwtAuthenticationToken — only then is the `act` claim resolvable. Without
        // this registration the guard never runs and impersonation grants full write access as the
        // target (the documented read-only model was dead code). See ImpersonationGuard.
        http.addFilterAfter(new ImpersonationGuard(objectMapper), AuthorizationFilter.class);

        http.authorizeHttpRequests(requests -> {
            // CORS preflight requests must be permitted for cross-origin requests to work.
            // Without this, OPTIONS requests are rejected with 403 before CORS headers can be added.
            requests.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
            // The container's ERROR dispatch (forward to /error) is re-secured by this chain. Without
            // permitting it, an error raised on a protected endpoint (e.g. a 403 from the CsrfFilter)
            // gets overwritten by a 401 when the anonymous /error forward is denied. Permit it so the
            // ORIGINAL status is preserved. The error view carries no sensitive data.
            requests.requestMatchers("/error").permitAll();
            // NOTE: /webhooks/**, /oauth/callback/**, /api/workers/** and /actuator/health|info are
            // claimed by higher-precedence chains and NEVER reach this fallback chain:
            //   - /webhooks/** + /oauth/callback/**  → workerHubSecurityFilterChain (the
            //     highest-precedence chain that matches by path)
            //   - /oauth2/authorization/** + /login/oauth2/code/** + /auth/login|error → AuthSecurityConfig
            // Spring dispatches to the FIRST matching SecurityFilterChain only, so permitAll rules for
            // those paths here would be dead code. Their controller/handshake-layer auth (HMAC, shared
            // token, signed state, worker JWT) is unaffected.
            // Dev-only: permit the dev trigger endpoint when explicitly enabled (defaults to false).
            if (devTriggerEnabled) {
                requests.requestMatchers(DEV_TRIGGER_MATCHER).permitAll();
            }
            // Dev-only: permit the passwordless dev sign-in (pre-auth POST) when explicitly enabled
            // (defaults to false; fail-closed in prod — see DevLoginService). Same canonical matcher as
            // the CSRF carve-out below so they cannot drift.
            if (devLoginEnabled) {
                requests.requestMatchers(DEV_LOGIN_MATCHER).permitAll();
            }
            // OpenAPI documentation endpoints (public for spec generation and dev access)
            requests.requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll();
            // Public auth discovery (core.auth module, ADR 0017): identity-provider list for the
            // login UI plus OIDC issuer metadata + JWKS. The OAuth login kickoff/callback paths
            // (/auth/login, /auth/error, /oauth2/authorization/**, /login/oauth2/code/**) are owned
            // by AuthSecurityConfig's higher-precedence chain and never reach this one.
            requests.requestMatchers(HttpMethod.GET, "/identity-providers").permitAll();
            requests.requestMatchers(HttpMethod.GET, "/.well-known/**").permitAll();
            // Public workspace provider discovery (workspace creation UI)
            requests.requestMatchers(HttpMethod.GET, "/workspaces/providers").permitAll();
            // Mentor endpoints gated by the MENTOR_ACCESS feature flag. MUST be matched BEFORE the
            // generic `/workspaces/*/**` permitAll below; otherwise the public-GET rule wins and
            // mentor reads become unauthenticated (feature-flag bypass — see #1071).
            requests.requestMatchers("/workspaces/*/mentor/**").hasAuthority(FeatureFlag.MENTOR_ACCESS.key());
            // Public read for slugged workspace paths (filter enforces membership/public visibility).
            requests.requestMatchers(HttpMethod.GET, "/workspaces/*/**").permitAll();
            // Registry/listing stays authenticated to avoid leaking tenant directory.
            requests.requestMatchers(HttpMethod.GET, "/workspaces", "/workspaces/")
                    .authenticated();
            // Non-GET workspace operations still require authentication.
            requests.requestMatchers("/workspaces/**").authenticated();
            // Public endpoints
            requests.requestMatchers(HttpMethod.GET, "/contributors").permitAll();
            // User account management requires authentication
            requests.requestMatchers("/user/**").authenticated();
            // Default: require authentication for all other endpoints
            requests.anyRequest().authenticated();
        });

        return http.build();
    }

    /**
     * Double-submit CSRF cookie repository. The token cookie is JS-readable (httpOnly=false) so the SPA
     * can echo it in the {@code X-XSRF-TOKEN} header. The {@code __Host-} name prefix forces the browser
     * to keep the cookie host-only + Secure + Path=/ (no Domain), so a sibling subdomain cannot "toss" a
     * forged {@code XSRF-TOKEN} cookie onto this host and defeat the double-submit check — matching the
     * un-tossable {@code __Host-} access cookie ({@link AuthProperties#DEFAULT_COOKIE_NAME}).
     */
    private CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(csrfCookieName(cookieSecure));
        repository.setCookieCustomizer(
                cookie -> cookie.secure(cookieSecure).path("/").sameSite("Lax"));
        return repository;
    }

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /**
     * Single canonical matcher for the optional dev-trigger surface, shared by the authorize rules and
     * the CSRF predicate so they cannot diverge. The webhook / OAuth-callback / {@code /login/oauth2/code/}
     * paths need no CSRF skip here: they are owned by the higher-precedence worker-hub and oauth2Login
     * chains and never reach this chain.
     */
    static final RequestMatcher DEV_TRIGGER_MATCHER =
            PathPatternRequestMatcher.withDefaults().matcher("/api/dev/**");

    /**
     * Single canonical matcher for the optional passwordless dev sign-in, shared by the authorize rule
     * and the CSRF predicate. This development-only bypass is absent in production.
     */
    static final RequestMatcher DEV_LOGIN_MATCHER =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/dev-login");

    /** Unsafe requests require CSRF unless they use only bearer auth or an enabled dev endpoint. */
    private boolean requiresCsrf(jakarta.servlet.http.HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        // The resolver prefers cookies, so adding a bearer header must not bypass their CSRF check.
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7) && !hasAuthCookie(request)) {
            return false;
        }
        if (devTriggerEnabled && DEV_TRIGGER_MATCHER.matches(request)) {
            return false;
        }
        if (devLoginEnabled && DEV_LOGIN_MATCHER.matches(request)) {
            return false;
        }
        return true;
    }

    private boolean hasAuthCookie(jakarta.servlet.http.HttpServletRequest request) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            if (authCookieName.equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH"));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "X-Requested-With",
                "Origin",
                "X-XSRF-TOKEN",
                "X-Impersonation-Allow-Writes"));
        configuration.setExposedHeaders(
                List.of(ReplicaIdentityFilter.HEADER_NAME, RequestCorrelationFilter.HEADER_NAME));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
