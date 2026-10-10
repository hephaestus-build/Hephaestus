package de.tum.cit.aet.hephaestus.core.auth.provider;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.core.auth.spi.IdentityProviderCatalog;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
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
 * <p>A small {@code expireAfterWrite} cache absorbs read load; an admin edit/disable calls
 * {@link #evict(String)} for immediate effect.
 */
public class LoginProviderClientRegistrationRepository
        implements ClientRegistrationRepository, Iterable<ClientRegistration>, IdentityProviderCatalog {

    private final LoginProviderRepository loginProviderRepository;
    private final OutlineOriginPolicy outlineOriginPolicy;

    private final String callbackBase;

    private final Cache<String, ClientRegistration> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(256)
            .build();

    public LoginProviderClientRegistrationRepository(
            LoginProviderRepository loginProviderRepository,
            String callbackBase,
            OutlineOriginPolicy outlineOriginPolicy) {
        this.loginProviderRepository = loginProviderRepository;
        this.outlineOriginPolicy = outlineOriginPolicy;
        this.callbackBase = callbackBase;
    }

    @Override
    public @Nullable ClientRegistration findByRegistrationId(String registrationId) {
        ClientRegistration cached = cache.getIfPresent(registrationId);
        if (cached != null) {
            return cached;
        }
        return loginProviderRepository
                .findByRegistrationId(registrationId)
                .filter(LoginProvider::isEnabled)
                .filter(this::isApproved)
                .map(this::toRegistration)
                .map(reg -> {
                    cache.put(registrationId, reg);
                    return reg;
                })
                .orElse(null);
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
        return loginProviderRepository.findByEnabledTrueOrderByDisplayNameAsc().stream()
                .filter(this::isApproved)
                .map(this::toRegistration)
                .toList();
    }

    @Override
    public boolean hasEnabledPrimarySignInProvider() {
        return loginProviderRepository.existsByEnabledTrueAndTypeIn(
                Set.of(LoginProvider.ProviderType.GITHUB, LoginProvider.ProviderType.GITLAB));
    }

    private boolean isApproved(LoginProvider provider) {
        return (provider.getType() != LoginProvider.ProviderType.OUTLINE
                || outlineOriginPolicy.allows(provider.getBaseUrl()));
    }

    private ClientRegistration toRegistration(LoginProvider provider) {
        String base = provider.getBaseUrl().replaceAll("/+$", "");
        ClientRegistration.Builder builder = ClientRegistration.withRegistrationId(provider.getRegistrationId())
                .clientId(provider.getClientId())
                .clientSecret(provider.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(callbackBase + "/login/oauth2/code/" + provider.getRegistrationId())
                .scope(provider.getScopes().trim().split("\\s+"))
                .userNameAttributeName("id")
                .clientName(provider.getDisplayName());

        if (provider.getType() == LoginProvider.ProviderType.GITHUB) {
            // github.com only (GHE is out of scope). The user API lives on the api. host, not the OAuth host.
            builder.authorizationUri("https://github.com/login/oauth/authorize")
                    .tokenUri("https://github.com/login/oauth/access_token")
                    .userInfoUri("https://api.github.com/user");
        } else if (provider.getType() == LoginProvider.ProviderType.SLACK) {
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
        } else if (provider.getType() == LoginProvider.ProviderType.OUTLINE) {
            // Outline (self-hosted) is a plain OAuth2 provider (v0.77+), NOT OIDC: no id_token, no
            // discovery, no real userinfo endpoint. The userInfoUri points at POST /api/auth.info —
            // Spring requires a userinfo endpoint for oauth2Login, and OutlineAuthInfoUserService
            // (routed by provider type in AuthSecurityConfig) actually calls it correctly (POST +
            // bearer token) and flattens {data:{user,team}} into principal attributes. The principal
            // keys on the immutable Outline user UUID ("id") — nOAuth defence, never name/email.
            builder.authorizationUri(base + "/oauth/authorize")
                    .tokenUri(base + "/oauth/token")
                    .userInfoUri(base + "/api/auth.info");
        } else {
            // GitLab (gitlab.com or self-hosted) — all endpoints hang off the instance base URL.
            builder.authorizationUri(base + "/oauth/authorize")
                    .tokenUri(base + "/oauth/token")
                    .userInfoUri(base + "/api/v4/user");
        }
        return builder.build();
    }
}
