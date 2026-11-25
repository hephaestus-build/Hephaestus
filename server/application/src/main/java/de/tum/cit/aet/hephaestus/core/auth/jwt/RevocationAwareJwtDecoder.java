package de.tum.cit.aet.hephaestus.core.auth.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import de.tum.cit.aet.hephaestus.core.ControlledClock;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.core.web.ApiPath;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Validates JWTs and checks revocation on every request. Only revoked verdicts are cached: revocation
 * is irreversible, so cache expiry affects replay load rather than how quickly logout takes effect.
 *
 * <p>Invalid credentials use {@link BadJwtException} (401). Revocation-store failures deny access with
 * {@link JwtException}, which Spring maps to an authentication service failure rather than logout.
 *
 * <p>A revoked browser token is recorded as a {@code JWT_REVOKED} failure once per refusal reason and cache entry.
 * A late request cannot suppress the audit of a later refresh replay.
 */
public class RevocationAwareJwtDecoder implements JwtDecoder {

    public static final String CACHE_NAME = "auth_jwt_revoked";
    private static final Logger log = LoggerFactory.getLogger(RevocationAwareJwtDecoder.class);

    private final NimbusJwtDecoder delegate;
    private final JwtSigningKeyService keyService;
    private final AuthProperties properties;
    private final CacheManager cacheManager;
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
        this(
                keyService,
                admission,
                authEventLogger,
                properties,
                cacheManager,
                clock,
                metrics,
                webAppAudience(properties));
    }

    private RevocationAwareJwtDecoder(
            JwtSigningKeyService keyService,
            AccessTokenAdmission admission,
            AuthEventLogger authEventLogger,
            AuthProperties properties,
            CacheManager cacheManager,
            Clock clock,
            AuthMetrics metrics,
            OAuth2TokenValidator<Jwt> audience) {
        this.delegate = localSignatureDecoder(keyService, properties, audience);
        this.keyService = keyService;
        this.properties = properties;
        this.cacheManager = cacheManager;
        this.admission = admission;
        this.authEventLogger = authEventLogger;
        this.cache = Objects.requireNonNull(cacheManager.getCache(CACHE_NAME));
        this.clock = ControlledClock.operationalClock(clock);
        this.metrics = metrics;
    }

    /**
     * A decoder whose tokens must carry another audience, such as the workspace MCP endpoint's (ADR 0110).
     * Signature, issuer, deadline and revocation checks stay those of this decoder.
     */
    public RevocationAwareJwtDecoder forAudience(OAuth2TokenValidator<Jwt> audience) {
        return new RevocationAwareJwtDecoder(
                keyService, admission, authEventLogger, properties, cacheManager, clock, metrics, audience);
    }

    /** Signature and claim validation without the database-backed revocation check. */
    public static NimbusJwtDecoder localSignatureDecoder(JwtSigningKeyService keyService, AuthProperties properties) {
        return localSignatureDecoder(keyService, properties, webAppAudience(properties));
    }

    private static NimbusJwtDecoder localSignatureDecoder(
            JwtSigningKeyService keyService, AuthProperties properties, OAuth2TokenValidator<Jwt> audience) {
        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        JWSKeySelector<SecurityContext> selector = new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, keyService);
        processor.setJWSKeySelector(selector);
        NimbusJwtDecoder nimbus = new NimbusJwtDecoder(processor);
        nimbus.setJwtValidator(buildValidator(properties, audience));
        return nimbus;
    }

    /**
     * The web app's token: the REST API refuses every token that does not name the web app's audience, and every MCP
     * client token. An MCP token is the only one with a {@code scope} claim, so the REST API refuses it by that claim
     * whatever audiences the deployment configures (ADR 0110 update of 2026-11-25).
     */
    private static OAuth2TokenValidator<Jwt> webAppAudience(AuthProperties properties) {
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(properties.audience()));
        OAuth2TokenValidator<Jwt> notMcp = jwt -> jwt.hasClaim("scope")
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN, "an MCP client token never reaches the REST API", null))
                : OAuth2TokenValidatorResult.success();
        return new DelegatingOAuth2TokenValidator<>(audience, notMcp);
    }

    private static OAuth2TokenValidator<Jwt> buildValidator(
            AuthProperties properties, OAuth2TokenValidator<Jwt> audience) {
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefault();
        OAuth2TokenValidator<Jwt> issuer = new JwtClaimValidator<String>(
                JwtClaimNames.ISS,
                iss -> iss != null && iss.equals(properties.issuer().toString()));
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
        try {
            AccessTokenAdmission.Refusal refusal = cache.get(jti, AccessTokenAdmission.Refusal.class);
            if (refusal == null) {
                refusal = admission.admit(jti, clock.instant());
            }
            if (refusal != null) {
                cache.putIfAbsent(jti, refusal);
                recordRefusal(jti, refusal.token());
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
    private void recordRefusal(UUID jti, @Nullable IssuedJwt token) {
        if (token == null
                || token.getSessionId() != null
                || token.getRevokedAt() == null
                || !token.getExpiresAt().isAfter(clock.instant())) {
            return;
        }
        boolean rotated = token.getRevokedReason() == IssuedJwt.RevokedReason.ROTATE;
        boolean superseded = token.getRevokedReason() == IssuedJwt.RevokedReason.SUPERSEDED;
        boolean reuse = (rotated || superseded) && isRefreshRequest();
        String reason = reuse
                ? "browser_token_reuse"
                : rotated
                        ? "rotated_token_presented"
                        : superseded ? "superseded_token_presented" : "revoked_token_presented";
        if (cache.putIfAbsent(new AuditKey(jti, reason), true) != null) {
            return;
        }
        authEventLogger
                .event(AuthEvent.EventType.JWT_REVOKED, AuthEvent.Result.FAILURE)
                .account(token.getAccountId())
                .failureReason(reason)
                .details("{\"revokedReason\":\"" + token.getRevokedReason() + "\"}")
                .record();
        if (reuse) {
            log.warn("auth.jwt: a retired browser token was presented for refresh; accountId={}", token.getAccountId());
        }
    }

    private record AuditKey(UUID jti, String reason) {}

    private static boolean isRefreshRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            return "POST".equals(request.getMethod()) && "/auth/refresh".equals(ApiPath.relative(request));
        }
        return false;
    }

    private static JwtException revokedException() {
        return new BadJwtException("token has been revoked");
    }
}
