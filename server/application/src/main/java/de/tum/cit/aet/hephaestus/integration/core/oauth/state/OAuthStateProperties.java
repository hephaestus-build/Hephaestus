package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed binding for {@code hephaestus.integration.oauth-state.*} — the HMAC + single-use settings
 * for OAuth {@code state} tokens.
 *
 * <p>Sibling of {@code hephaestus.integration.oauth} (callback redirects, bound by
 * {@code OAuthCallbackProperties}); kept separate so the security-sensitive HMAC secret has its
 * own namespace.
 *
 * <p>{@code secret} is nullable here: production refuses to start without it, and outside production
 * {@link HmacOAuthStateService} generates a temporary key.
 *
 * @param secret Key that signs OAuth state tokens. Required in production; outside production a temporary key is
 *     generated.
 * @param ttl freshness window for issued state tokens (default 10 minutes)
 * @param nonceRetention how long consumed/expired nonce rows are kept before the daily sweep
 *     prunes them (default 7 days)
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.integration.oauth-state")
public record OAuthStateProperties(
        @Nullable String secret,
        @DefaultValue("PT10M") Duration ttl,
        @DefaultValue("P7D") Duration nonceRetention) {
    public OAuthStateProperties {
        if (ttl == null) {
            ttl = Duration.ofMinutes(10);
        }
        if (nonceRetention == null) {
            nonceRetention = Duration.ofDays(7);
        }
    }
}
