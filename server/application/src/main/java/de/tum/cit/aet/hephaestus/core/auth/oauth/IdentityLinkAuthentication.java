package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.jwt.CookieBearerTokenResolver;
import de.tum.cit.aet.hephaestus.core.auth.jwt.RevocationAwareJwtDecoder;
import de.tum.cit.aet.hephaestus.core.auth.stepup.RecentSignInPolicy;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

/**
 * Resolves the account that may attach a new identity, for the two points of the linking flow that run
 * outside the resource-server chain.
 *
 * <p>The OAuth chain is stateless and {@code permitAll}, so no {@code SecurityContext} exists on either
 * the kickoff or the callback. The access cookie is validated here with the same primitives the
 * resource-server chain uses ({@link CookieBearerTokenResolver} → {@link RevocationAwareJwtDecoder} →
 * the shared authentication converter), which is also what lets the recent-sign-in decision read the
 * very same factor authority it reads for an ordinary request.
 *
 * <p>Linking is gated because attaching an identity adds a permanent second way into the account: a
 * hijacked session must not be able to install one.
 */
@ConditionalOnServerRole
@Service
public class IdentityLinkAuthentication {
    private static final Logger log = LoggerFactory.getLogger(IdentityLinkAuthentication.class);

    private final CookieBearerTokenResolver bearerTokenResolver;
    private final JwtDecoder jwtDecoder;
    private final Converter<Jwt, AbstractAuthenticationToken> authenticationConverter;
    private final RecentSignInPolicy recentSignInPolicy;

    public IdentityLinkAuthentication(
            CookieBearerTokenResolver bearerTokenResolver,
            RevocationAwareJwtDecoder jwtDecoder,
            Converter<Jwt, AbstractAuthenticationToken> authenticationConverter,
            RecentSignInPolicy recentSignInPolicy) {
        this.bearerTokenResolver = bearerTokenResolver;
        this.jwtDecoder = jwtDecoder;
        this.authenticationConverter = authenticationConverter;
        this.recentSignInPolicy = recentSignInPolicy;
    }

    /**
     * The account id from the access cookie, or {@code null} when there is no token, the token is
     * invalid or revoked, or the session is an impersonation — an operator must not link an identity
     * into someone else's account, and the {@code act} claim is the only signal that they are one.
     *
     * @throws de.tum.cit.aet.hephaestus.core.auth.stepup.StepUpRequiredException when the session is
     *     valid but the sign-in behind it is no longer recent.
     */
    @Nullable
    public Long resolveAuthenticatedAccountId(HttpServletRequest request) {
        String token = bearerTokenResolver.resolve(request);
        if (token == null || token.isBlank()) {
            return null;
        }
        Jwt jwt;
        Long accountId;
        try {
            jwt = jwtDecoder.decode(token);
            if (jwt.hasClaim("act")) {
                return null;
            }
            accountId = Long.parseLong(jwt.getSubject());
        } catch (JwtException | NumberFormatException ex) {
            log.warn("auth.link: token rejected: {}", ex.getMessage());
            return null;
        }
        recentSignInPolicy.require(
                authenticationConverter.convert(jwt), AuthEvent.EventType.IDENTITY_LINKED, accountId);
        return accountId;
    }
}
