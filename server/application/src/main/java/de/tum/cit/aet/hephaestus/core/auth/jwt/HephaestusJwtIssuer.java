package de.tum.cit.aet.hephaestus.core.auth.jwt;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints Hephaestus's cookie-bound access JWTs.
 *
 * <h2>Claim shape</h2>
 * <pre>
 * iss                — {@link AuthProperties#issuer}
 * sub                — {@code Account.id} as a decimal string
 * aud                — default {@link AuthProperties#audience} (caller can override per-issue)
 * jti                — fresh UUID; also INSERTed into {@code issued_jwt} so revocation can short-circuit
 * iat                — {@link Clock#instant()} of the issuing pod
 * exp                — {@code iat + accessTtl}
 * preferred_username — login (standard OIDC claim)
 * roles              — flat string array of granted roles (Hephaestus-specific; the authority converter reads it)
 * given_name         — first name; only when known
 * act                — RFC 8693 actor object {@code {"sub": "<impersonator_id>"}}; absent when not impersonating
 * imp_exp            — absolute impersonation ceiling (epoch seconds); see {@link TokenConstraints}
 * session_exp        — absolute session ceiling (epoch seconds); see {@link TokenConstraints}
 * auth_time          — last interactive sign-in (epoch seconds, standard OIDC claim); see {@link TokenConstraints}
 * </pre>
 *
 * <h2>Issuance contract</h2>
 * Every successful {@link #issue} call is paired with an {@code issued_jwt} INSERT in the
 * same transaction. The {@code jti} is committed before the cookie is set on the response —
 * if the DB write fails, no JWT escapes.
 */
@ConditionalOnServerRole
@Service
public class HephaestusJwtIssuer {

    private final JwtEncoder encoder;
    private final JwtSigningKeyService keyService;
    private final IssuedJwtRepository issuedJwtRepository;
    private final AuthProperties properties;
    private final Clock clock;

    public HephaestusJwtIssuer(
            JwtSigningKeyService keyService,
            IssuedJwtRepository issuedJwtRepository,
            AuthProperties properties,
            Clock clock) {
        this.keyService = keyService;
        this.issuedJwtRepository = issuedJwtRepository;
        this.properties = properties;
        this.clock = clock;
        this.encoder = buildEncoder(keyService);
    }

    private static JwtEncoder buildEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Mint a new access JWT for {@code principal}, recording the {@code jti} in {@code issued_jwt} in the
     * same transaction. Claim shape: see the class Javadoc.
     *
     * <p>The token's {@code exp} is capped at the earliest of {@code now + accessTtl} and the ceilings in
     * {@code constraints}, and those ceilings are also carried as claims, so {@code AuthSessionService
     * .refresh} re-caps the rotated token at the same instants — a rolling silent refresh can extend
     * neither an impersonation nor a session (OWASP absolute timeout).
     *
     * @param principal   account id + login + roles to bake in.
     * @param constraints the authority and deadlines carried across rotations.
     * @param request     used to capture {@code user_agent} + remote IP into the revocation row.
     */
    @Transactional
    public Token issue(JwtPrincipal principal, TokenConstraints constraints, @Nullable HttpServletRequest request) {
        Long impersonatorId = constraints.impersonatorId();
        Instant impersonationExpiresAt = constraints.impersonationExpiresAt();
        Instant sessionExpiresAt = constraints.sessionExpiresAt();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTtl());
        if (impersonationExpiresAt != null && impersonationExpiresAt.isBefore(expiresAt)) {
            expiresAt = impersonationExpiresAt;
        }
        if (sessionExpiresAt != null && sessionExpiresAt.isBefore(expiresAt)) {
            expiresAt = sessionExpiresAt;
        }
        UUID jti = UUID.randomUUID();
        JWK signingKey = keyService.currentSigningKey();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer().toString())
                .subject(String.valueOf(principal.accountId()))
                .audience(List.of(properties.audience()))
                .id(jti.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("preferred_username", principal.login())
                .claim("roles", List.copyOf(principal.roles()));
        if (principal.givenName() != null) {
            claims.claim("given_name", principal.givenName());
        }
        if (impersonatorId != null) {
            claims.claim("act", Map.of("sub", String.valueOf(impersonatorId)));
        }
        if (impersonationExpiresAt != null) {
            // Absolute impersonation ceiling (epoch seconds), constant across refreshes. refresh reads
            // it to auto-exit; it is NOT the per-token exp (which is min(now+accessTtl, this)).
            claims.claim("imp_exp", impersonationExpiresAt.getEpochSecond());
        }
        if (sessionExpiresAt != null) {
            // Absolute session ceiling (epoch seconds), constant across refreshes (OWASP absolute timeout).
            claims.claim("session_exp", sessionExpiresAt.getEpochSecond());
        }
        if (constraints.authTime() != null) {
            // Stamped once by the login that completed the OAuth dance and copied verbatim through every
            // rotation: a silent refresh must not make a session look freshly signed in.
            claims.claim("auth_time", constraints.authTime().getEpochSecond());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256)
                .keyId(signingKey.getKeyID())
                .build();
        Jwt jwt = encoder.encode(JwtEncoderParameters.from(header, claims.build()));

        IssuedJwt row = new IssuedJwt(jti, principal.accountId(), expiresAt);
        if (request != null) {
            String ua = request.getHeader("User-Agent");
            if (ua != null && ua.length() > 512) {
                ua = ua.substring(0, 512);
            }
            row.setUserAgent(ua);
            // Audit IP is best-effort. The ip_inet column is Postgres `inet`; an unparseable value
            // (proxy rewrite, spoofed harness, IPv6 scope id) would make the issued_jwt INSERT throw
            // and — because issuance is transactional — block login entirely. Drop an invalid address
            // to null so bad audit metadata can never fail token issuance.
            row.setIpInet(sanitizeIp(request.getRemoteAddr()));
        }
        issuedJwtRepository.save(row);

        return new Token(jwt.getTokenValue(), jti, expiresAt);
    }

    /**
     * Return {@code raw} only if it parses as a literal IP address; otherwise null. The char pre-check
     * (only {@code 0-9 a-f . : %}) keeps {@code getByName} off DNS for the inputs we actually see — this
     * is always {@code request.getRemoteAddr()}, a numeric peer IP, never a hostname. (A contrived
     * all-hex label like {@code "cafe"} would still be a resolvable name, so this is a fast-path filter,
     * not a hard guarantee.) Keeps a malformed audit IP from failing the {@code ip_inet} (Postgres
     * {@code inet}) INSERT and thus blocking issuance.
     */
    @Nullable
    static String sanitizeIp(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == '.' || c == ':' || c == '%')) {
            return null;
        }
        try {
            InetAddress.getByName(raw);
            return raw;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * The freshly-minted token. {@link #value()} is what goes into the cookie; {@link #jti()}
     * is exposed so callers can correlate audit rows.
     */
    public record Token(String value, UUID jti, Instant expiresAt) {}
}
