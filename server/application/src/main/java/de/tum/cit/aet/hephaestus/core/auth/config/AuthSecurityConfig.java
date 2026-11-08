package de.tum.cit.aet.hephaestus.core.auth.config;

import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.oauth.AuthIntentCookie;
import de.tum.cit.aet.hephaestus.core.auth.oauth.CookieOAuth2AuthorizationRequestRepository;
import de.tum.cit.aet.hephaestus.core.auth.oauth.GitHubEmailOAuth2UserService;
import de.tum.cit.aet.hephaestus.core.auth.oauth.HephaestusAuthFailureHandler;
import de.tum.cit.aet.hephaestus.core.auth.oauth.HephaestusAuthSuccessHandler;
import de.tum.cit.aet.hephaestus.core.auth.oauth.OutlineAuthInfoUserService;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitFilter;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.core.security.SecurityHeaders;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the OAuth login flow on a dedicated, medium-precedence
 * {@link SecurityFilterChain}.
 *
 * <p>Order: {@code HIGHEST_PRECEDENCE + 10} — runs after the ingress chain
 * ({@code HIGHEST_PRECEDENCE + 4}) but before the resource-server chain (default order).
 * Scope is strictly the URLs Spring's {@code oauth2Login} cares about plus the
 * {@code /auth/login} kickoff endpoint:
 *
 * <ul>
 *   <li>{@code GET /oauth2/authorization/{registrationId}} — Spring's initiation filter.</li>
 *   <li>{@code GET /login/oauth2/code/{registrationId}} — Spring's callback filter.</li>
 *   <li>{@code GET /auth/login} — our intent-stamping wrapper that 302s to the initiation.</li>
 *   <li>{@code GET /auth/error} — public error page rendered by the SPA.</li>
 * </ul>
 *
 * <p>Stateless. {@link CookieOAuth2AuthorizationRequestRepository} carries the in-flight
 * {@code OAuth2AuthorizationRequest} across the IdP round-trip via an AES-GCM-sealed
 * cookie — the standard {@code HttpSessionOAuth2AuthorizationRequestRepository} would
 * silently create a session that the callback request on a different pod could not see.
 *
 * <p>Everything outside the URLs above is handled by the resource-server chain, which
 * validates our own ES256 JWTs via {@code RevocationAwareJwtDecoder} (ADR 0017).
 */
@ConditionalOnServerRole
@Configuration
public class AuthSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthSecurityConfig.class);

    private final LoginProviderRepository loginProviderRepository;
    private final RestClient.Builder restClients;
    private final RestTemplateBuilder restTemplates;
    private final WebClient.Builder webClients;
    private final ScmServerEndpointPolicy scmEndpoints;

    public AuthSecurityConfig(
            LoginProviderRepository loginProviderRepository,
            RestClient.Builder restClients,
            RestTemplateBuilder restTemplates,
            WebClient.Builder webClients,
            ScmServerEndpointPolicy scmEndpoints) {
        this.loginProviderRepository = loginProviderRepository;
        this.restClients = restClients;
        this.restTemplates = restTemplates;
        this.webClients = webClients;
        this.scmEndpoints = scmEndpoints;
    }

    @Bean
    public SecretKey authStateCookieKey(AuthProperties properties, Environment environment) {
        return new SecretKeySpec(resolveStateCookieKey(properties, environment), "AES");
    }

    @Bean
    public CookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository(
            @Qualifier("authStateCookieKey") SecretKey key) {
        return new CookieOAuth2AuthorizationRequestRepository(key.getEncoded());
    }

    @Bean
    public AuthIntentCookie authIntentCookie(@Qualifier("authStateCookieKey") SecretKey key) {
        return new AuthIntentCookie(key.getEncoded());
    }

    /**
     * Resolver that adds PKCE (RFC 7636 / RFC 9700) to every login authorization request. Spring
     * only auto-enables PKCE for <em>public</em> clients; our login registrations are confidential
     * ({@code CLIENT_SECRET_BASIC}), so we opt in explicitly. RFC 9700 (Jan 2025) requires PKCE for
     * all OAuth clients — for GitHub (plain OAuth2, no {@code id_token}/nonce) it is the primary
     * defence against authorization-code injection alongside {@code state}. The {@code code_verifier}
     * is stored on the {@code OAuth2AuthorizationRequest} and round-trips inside the sealed
     * auth-request cookie ({@link CookieOAuth2AuthorizationRequestRepository}).
     *
     * <p>Package-private so {@code AuthSecurityConfigTest} can assert {@code code_challenge} is
     * actually emitted — removing the {@code withPkce()} line would otherwise silently drop the only
     * code-injection defense for the confidential GitHub client with no test failure.
     */
    static OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository repo) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    /**
     * Routes by the stored provider type. GitHub adds its primary verified email; Outline reads
     * identity with POST auth.info. The framework user-info service uses the guarded transport.
     */
    @Bean
    public OAuth2UserService<OAuth2UserRequest, OAuth2User> oauthUserService() {
        var fallback = new DefaultOAuth2UserService();
        fallback.setRestOperations(guardedUserInfoClient());
        var github = new GitHubEmailOAuth2UserService(restClients, fallback);
        var outline = new OutlineAuthInfoUserService(webClients);
        return request -> {
            ClientRegistration registration = request.getClientRegistration();
            if (isGitHub(registration)) {
                return github.loadUser(request);
            }
            if (isOutline(registration)) {
                return outline.loadUser(request);
            }
            return fallback.loadUser(request);
        };
    }

    /** Provider user-info calls check the address that they connect to. */
    private RestTemplate guardedUserInfoClient() {
        return restTemplates
                .requestFactory(this::guardedRequestFactory)
                .errorHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    private ClientHttpRequestFactory guardedRequestFactory() {
        return new ReactorClientHttpRequestFactory(
                WebClientConnectors.httpClient(scmEndpoints.resolverGroup()).followRedirect(false));
    }

    /** The code exchange sends the client secret, so it checks the address that it connects to as well. */
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> guardedTokenClient() {
        var client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClients
                .clone()
                .requestFactory(guardedRequestFactory())
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        return client;
    }

    /** By the stored type: a GitLab whose URL merely begins like GitHub's must not get GitHub's client. */
    private boolean isGitHub(ClientRegistration registration) {
        return hasType(registration, LoginProvider.ProviderType.GITHUB);
    }

    private boolean isOutline(ClientRegistration registration) {
        return hasType(registration, LoginProvider.ProviderType.OUTLINE);
    }

    private boolean hasType(ClientRegistration registration, LoginProvider.ProviderType type) {
        return loginProviderRepository
                .findByRegistrationId(registration.getRegistrationId())
                .map(provider -> provider.getType() == type)
                .orElse(false);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public SecurityFilterChain oauthLoginSecurityFilterChain(
            HttpSecurity http,
            CookieOAuth2AuthorizationRequestRepository cookieRepo,
            HephaestusAuthSuccessHandler successHandler,
            HephaestusAuthFailureHandler failureHandler,
            AuthRateLimitFilter authRateLimitFilter,
            ClientRegistrationRepository clientRegistrationRepository)
            throws Exception {
        http.securityMatcher("/oauth2/authorization/**", "/login/oauth2/code/**", "/auth/login", "/auth/error")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(req -> {
                    req.requestMatchers("/auth/error").permitAll();
                    req.anyRequest().permitAll(); // oauth2Login handles the actual auth
                })
                .oauth2Login(oauth -> {
                    oauth.tokenEndpoint(token -> token.accessTokenResponseClient(guardedTokenClient()));
                    // Explicit loginPage: the SPA owns the login UI, and without this Spring Security's
                    // OAuth2LoginConfigurer ENUMERATES the ClientRegistrationRepository at config time to build
                    // default login links. That repo is DB-backed (LoginProviderClientRegistrationRepository
                    // reads the login_provider store), so the enumeration opens a JDBC connection during context
                    // refresh — which has no DB during the Paketo CDS-training build run and fails the image
                    // build. Setting a loginPage skips the default-page generation and that startup DB hit.
                    oauth.loginPage("/login");
                    oauth.authorizationEndpoint(endpoint -> endpoint.authorizationRequestRepository(cookieRepo)
                            .authorizationRequestResolver(pkceResolver(clientRegistrationRepository)));
                    // GitHub: enrich attributes with the primary+verified email from /user/emails so
                    // VerifiedEmailResolver can stamp primaryEmailVerifiedAt. OIDC providers are untouched.
                    oauth.userInfoEndpoint(userInfo -> userInfo.userService(oauthUserService()));
                    oauth.successHandler(successHandler);
                    oauth.failureHandler(failureHandler);
                });

        // Same header set as the resource-server chain — this chain serves /auth/error to the SPA.
        SecurityHeaders.apply(http);
        // Rate-limit GET /oauth2/authorization/* (keyed by client IP). The filter no-ops on the
        // other paths this chain matches.
        http.addFilterBefore(authRateLimitFilter, AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Resolve the AES-256-GCM key for the two short-lived state cookies. Production must set
     * {@code hephaestus.auth.state-cookie-key} to a base64-encoded 32-byte value; absence is
     * tolerated only for dev / CI where we generate a per-boot ephemeral key and log a
     * warning (in-flight logins are abandoned across restarts).
     *
     * <p>In the {@code prod} profile a blank key is fatal (fail-closed) — an ephemeral key silently
     * invalidates every in-flight login on each pod restart and differs per replica.
     */
    static byte[] resolveStateCookieKey(AuthProperties properties, Environment environment) {
        if (!properties.stateCookieKey().isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(properties.stateCookieKey());
            if (decoded.length != 32) {
                throw new IllegalStateException(
                        "hephaestus.auth.state-cookie-key must decode to 32 bytes (256-bit AES). It decoded to "
                                + decoded.length);
            }
            return decoded;
        }
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException("hephaestus.auth.state-cookie-key is required in production. "
                    + "The server fails closed and does not start without it. "
                    + "Set it to a base64-encoded 32-byte (256-bit AES) value.");
        }
        byte[] ephemeral = new byte[32];
        new SecureRandom().nextBytes(ephemeral);
        if (environment.matchesProfiles("specs", "cds-training")) {
            log.debug("Created an ephemeral state-cookie key for artifact generation");
        } else {
            log.warn(
                    "auth: hephaestus.auth.state-cookie-key is unset. Hephaestus generated an ephemeral 256-bit key for this boot. "
                            + "In-flight logins do not survive a restart. Set the env var for stable behavior.");
        }
        return ephemeral;
    }
}
