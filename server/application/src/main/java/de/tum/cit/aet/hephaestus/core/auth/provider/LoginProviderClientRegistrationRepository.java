package de.tum.cit.aet.hephaestus.core.auth.provider;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.core.auth.spi.IdentityProviderCatalog;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Builds Spring Security login {@link ClientRegistration}s from the instance-scoped
 * {@code login_provider} store (ADR 0017). One shared registration per configured provider — GitHub,
 * GitLab.com, or a self-hosted GitLab — reused across all workspaces; there are no per-workspace login
 * apps. Replaces Boot's auto-configured {@code InMemoryClientRegistrationRepository} (which backs off
 * once this bean is present) and serves the sign-in picker via {@link IdentityProviderCatalog}.
 *
 * <p>Each lookup reads the row. A cached registration is reused only while it was built from that same row
 * version, so a row that another server replaced or edited never authenticates through its old registration.
 * The cache saves rebuilding (secret decryption, OIDC discovery), not the row read.
 */
public class LoginProviderClientRegistrationRepository
        implements ClientRegistrationRepository, Iterable<ClientRegistration>, IdentityProviderCatalog {

    private final LoginProviderRepository loginProviderRepository;
    private final OutlineOriginPolicy outlineOriginPolicy;
    private final ScmServerEndpointPolicy scmEndpoints;
    private final SystemSecrets secrets;
    private final OidcDiscovery discovery;

    private final String callbackBase;

    /** Marks a registration whose ID token Hephaestus validates against the issuer's discovered key set. */
    public static final String OIDC_PROVIDER = "hephaestus.oidc-provider";

    public static boolean isOidcProvider(ClientRegistration registration) {
        return Boolean.TRUE.equals(
                registration.getProviderDetails().getConfigurationMetadata().get(OIDC_PROVIDER));
    }

    private record Built(String version, ClientRegistration registration) {}

    private final Cache<String, Built> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(256)
            .build();

    public LoginProviderClientRegistrationRepository(
            LoginProviderRepository loginProviderRepository,
            String callbackBase,
            OutlineOriginPolicy outlineOriginPolicy,
            ScmServerEndpointPolicy scmEndpoints,
            SystemSecrets secrets,
            OidcDiscovery discovery) {
        this.loginProviderRepository = loginProviderRepository;
        this.discovery = discovery;
        this.secrets = secrets;
        this.outlineOriginPolicy = outlineOriginPolicy;
        this.scmEndpoints = scmEndpoints;
        this.callbackBase = callbackBase;
    }

    @Override
    public @Nullable ClientRegistration findByRegistrationId(String registrationId) {
        return loginProviderRepository
                .findByRegistrationId(registrationId)
                .filter(LoginProvider::isEnabled)
                .filter(this::isApproved)
                .flatMap(this::registrationOf)
                .orElse(null);
    }

    private Optional<ClientRegistration> registrationOf(LoginProvider provider) {
        String version = String.join(
                "|",
                String.valueOf(provider.getId()),
                provider.getType().name(),
                provider.getBaseUrl(),
                provider.getClientId(),
                provider.getScopes(),
                provider.getDisplayName(),
                String.valueOf(provider.getUpdatedAt()));
        Built built = cache.getIfPresent(provider.getRegistrationId());
        if (built != null && built.version().equals(version)) return Optional.of(built.registration());
        Optional<ClientRegistration> registration = toRegistration(provider);
        registration.ifPresent(value -> cache.put(provider.getRegistrationId(), new Built(version, value)));
        return registration;
    }

    /** Drop a cached registration after an admin edit/disable so the change takes effect at once. */
    public void evict(String registrationId) {
        cache.invalidate(registrationId);
    }

    @Override
    public Iterator<ClientRegistration> iterator() {
        return listRegistrations().iterator();
    }

    @Override
    public List<ClientRegistration> listRegistrations() {
        // A registration the cache holds is reused, so the public sign-in page reads no issuer itself.
        return loginProviderRepository.findByEnabledTrueOrderByDisplayNameAsc().stream()
                .filter(this::isApproved)
                .flatMap(provider -> registrationOf(provider).stream())
                .toList();
    }

    @Override
    public boolean hasEnabledPrimarySignInProvider() {
        return loginProviderRepository.existsByEnabledTrueAndTypeIn(LoginProvider.ProviderType.signingIn());
    }

    private boolean isApproved(LoginProvider provider) {
        if (provider.getType() == LoginProvider.ProviderType.GITLAB) return allowedGitLab(provider.getBaseUrl());
        return (provider.getType() != LoginProvider.ProviderType.OUTLINE
                || outlineOriginPolicy.allows(provider.getBaseUrl()));
    }

    /** A stored GitLab keeps following the current list: removing a host from it ends sign-in there. */
    private boolean allowedGitLab(String baseUrl) {
        try {
            scmEndpoints.validate(baseUrl);
            return true;
        } catch (IllegalArgumentException refused) {
            return false;
        }
    }

    /** Empty for an OIDC provider whose issuer cannot be read now. */
    private Optional<ClientRegistration> toRegistration(LoginProvider provider) {
        String base = provider.getBaseUrl().replaceAll("/+$", "");
        ClientRegistration.Builder builder = ClientRegistration.withRegistrationId(provider.getRegistrationId())
                .clientId(provider.getClientId())
                .clientSecret(provider.clientSecret(secrets))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(callbackBase + "/login/oauth2/code/" + provider.getRegistrationId())
                .scope(provider.getScopes().trim().split("\\s+"))
                .userNameAttributeName("id")
                .clientName(provider.getDisplayName());

        switch (provider.getType()) {
            case GITHUB ->
                // github.com only (GHE is out of scope). The user API lives on the api. host, not the OAuth host.
                builder.authorizationUri("https://github.com/login/oauth/authorize")
                        .tokenUri("https://github.com/login/oauth/access_token")
                        .userInfoUri("https://api.github.com/user");
            case SLACK ->
                // "Sign in with Slack" is OIDC: the id_token carries the stable subject (sub) and the verified
                // team_id claim that keys a Slack identity within its workspace. Setting jwkSetUri (+ the openid
                // scope, enforced in LoginProviderService) makes Spring Security take the OIDC login path and
                // validate the id_token JWS. userNameAttributeName MUST be "sub" — Slack has no top-level "id".
                // Endpoints hang off the slack.com base URL (the only Slack instance).
                builder.authorizationUri(base + "/openid/connect/authorize")
                        .tokenUri(base + "/api/openid.connect.token")
                        .userInfoUri(base + "/api/openid.connect.userInfo")
                        .jwkSetUri(base + "/openid/connect/keys")
                        .userNameAttributeName("sub");
            case OIDC -> {
                // No userinfo endpoint: the claims come from the validated ID token only, so no outbound call runs
                // outside the guard. The issuer makes OidcIdTokenValidator check the token's iss.
                // Without openid Spring takes the plain OAuth2 path and validates no ID token; a row that an
                // earlier release edited can lack it.
                if (!List.of(provider.getScopes().trim().split("\\s+")).contains("openid")) return Optional.empty();
                OidcDiscovery.Endpoints endpoints =
                        discovery.discover(provider.getBaseUrl()).endpoints();
                if (endpoints == null) return Optional.empty();
                builder.authorizationUri(endpoints.authorizationUri())
                        .tokenUri(endpoints.tokenUri())
                        .jwkSetUri(endpoints.jwkSetUri())
                        .issuerUri(endpoints.issuer())
                        .userNameAttributeName("sub")
                        .providerConfigurationMetadata(Map.of(OIDC_PROVIDER, true));
            }
            case OUTLINE ->
                // Outline (self-hosted) is a plain OAuth2 provider (v0.77+), NOT OIDC: no id_token, no
                // discovery, no real userinfo endpoint. The userInfoUri points at POST /api/auth.info —
                // Spring requires a userinfo endpoint for oauth2Login, and OutlineAuthInfoUserService
                // (routed by provider type in AuthSecurityConfig) actually calls it correctly (POST +
                // bearer token) and flattens {data:{user,team}} into principal attributes. The principal
                // keys on the immutable Outline user UUID ("id") — nOAuth defence, never name/email.
                builder.authorizationUri(base + "/oauth/authorize")
                        .tokenUri(base + "/oauth/token")
                        .userInfoUri(base + "/api/auth.info");
            case GITLAB ->
                // GitLab (gitlab.com or self-hosted) — all endpoints hang off the instance base URL.
                builder.authorizationUri(base + "/oauth/authorize")
                        .tokenUri(base + "/oauth/token")
                        .userInfoUri(base + "/api/v4/user");
        }
        return Optional.of(builder.build());
    }
}
