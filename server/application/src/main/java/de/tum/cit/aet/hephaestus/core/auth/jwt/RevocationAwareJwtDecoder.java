package de.tum.cit.aet.hephaestus.core.auth.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Validates JWTs and checks revocation on every request. Only revoked verdicts are cached: revocation
 * is irreversible, so cache expiry affects replay load rather than how quickly logout takes effect.
 *
 * <p>Invalid credentials use {@link BadJwtException} (401). Revocation-store failures deny access with
 * {@link JwtException}, which Spring maps to an authentication service failure rather than logout.
 *
 * <p>A revoked browser token is recorded as a {@code JWT_REVOKED} failure once per cache entry, so a burst
 * of tabs that present it adds one row, not one per request.
 */
public class RevocationAwareJwtDecoder implements JwtDecoder {

    public static final String CACHE_NAME = "auth_jwt_revoked";
    private static final Logger log = LoggerFactory.getLogger(RevocationAwareJwtDecoder.class);

    private final NimbusJwtDecoder delegate;
    private final AccessTokenAdmission admission;
    private final AuthEventLogger authEventLogger;
    private final Cache cache;
    private final Clock clock;
    private final AuthMetrics metrics;

    public RevocationAwareJwtDecoder(
            JwtSigningKeyService keyService,
            AccessTokenAdmission admission,
            AuthEventLogger authEventLogger,
            AuthProperties properties,
            CacheManager cacheManager,
            Clock clock,
            AuthMetrics metrics) {
        this.delegate = localSignatureDecoder(keyService, properties);
        this.admission = admission;
        this.authEventLogger = authEventLogger;
        this.cache = Objects.requireNonNull(cacheManager.getCache(CACHE_NAME));
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Signature and claim validation without the database-backed revocation check. */
    public static NimbusJwtDecoder localSignatureDecoder(JwtSigningKeyService keyService, AuthProperties properties) {
        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        JWSKeySelector<SecurityContext> selector = new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, keyService);
        processor.setJWSKeySelector(selector);
        NimbusJwtDecoder nimbus = new NimbusJwtDecoder(processor);
        nimbus.setJwtValidator(buildValidator(properties));
        return nimbus;
    }

    private static OAuth2TokenValidator<Jwt> buildValidator(AuthProperties properties) {
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefault();
        OAuth2TokenValidator<Jwt> issuer = new JwtClaimValidator<String>(
                JwtClaimNames.ISS,
                iss -> iss != null && iss.equals(properties.issuer().toString()));
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(properties.audience()));
        // A delegated token names one account as acting for another; nothing here may act for anyone.
        OAuth2TokenValidator<Jwt> notDelegated = jwt -> jwt.hasClaim("act")
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN, "delegated account sessions are not supported", null))
                : OAuth2TokenValidatorResult.success();
        return new DelegatingOAuth2TokenValidator<>(defaults, issuer, audience, notDelegated);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        Jwt jwt = delegate.decode(token);
        String jtiClaim = jwt.getId();
        if (jtiClaim == null) {
            throw new BadJwtException("missing jti");
        }
        UUID jti;
        try {
            jti = UUID.fromString(jtiClaim);
        } catch (IllegalArgumentException ex) {
            throw new BadJwtException("malformed jti", ex);
        }
        Boolean revoked = cache.get(jti, Boolean.class);
        if (Boolean.TRUE.equals(revoked)) {
            throw revokedException();
        }
        try {
            AccessTokenAdmission.Refusal refusal = admission.admit(jti, clock.instant());
            if (refusal != null) {
                // The cached verdict also dedupes the audit row: one per token and pod per cache entry.
                if (cache.putIfAbsent(jti, true) == null) {
                    recordRefusal(refusal.token());
                }
                throw revokedException();
            }
            return jwt;
        } catch (JwtException rethrow) {
            throw rethrow;
        } catch (RuntimeException dbError) {
            metrics.recordRevocationCheckFailed();
            log.error("auth.jwt: revocation lookup failed for jti={}", jti, dbError);
            throw new JwtException("revocation check failed", dbError);
        }
    }

    /**
     * Records a revoked browser token. An expired or unknown token records nothing, and an installed-client
     * session records its own reuse when its refresh secret returns.
     */
    private void recordRefusal(@Nullable IssuedJwt token) {
        if (token == null
                || token.getSessionId() != null
                || token.getRevokedAt() == null
                || !token.getExpiresAt().isAfter(clock.instant())) {
            return;
        }
        boolean reuse = token.getRevokedReason() == IssuedJwt.RevokedReason.ROTATE;
        authEventLogger
                .event(AuthEvent.EventType.JWT_REVOKED, AuthEvent.Result.FAILURE)
                .account(token.getAccountId())
                .failureReason(reuse ? "browser_token_reuse" : "revoked_token_presented")
                .details("{\"revokedReason\":\"" + token.getRevokedReason() + "\"}")
                .record();
        if (reuse) {
            log.warn("auth.jwt: a rotated-away browser token was presented again; accountId={}", token.getAccountId());
        }
    }

    private static JwtException revokedException() {
        return new BadJwtException("token has been revoked");
    }
}
