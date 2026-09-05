package de.tum.cit.aet.hephaestus.core.auth.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the current Hephaestus account from the validated JWT in the security context.
 *
 * <p>Our JWTs carry {@code sub = Account.id} (decimal), {@code jti} (UUID), and optionally
 * {@code act} (impersonator id). This is the single place those are parsed so controllers
 * stay declarative.
 */
@NamedInterface("current-account")
public final class CurrentAccount {

    private CurrentAccount() {}

    /** The authenticated account id ({@code sub}), or throws 401 if absent / unparseable. */
    public static Long requireId() {
        Jwt jwt = requireJwt();
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token subject is not an account id", e);
        }
    }

    /** Whether a validated token is present at all — i.e. someone, rather than a background job, is acting. */
    public static boolean isAuthenticated() {
        return jwtOrNull() != null;
    }

    /**
     * The authenticated account id ({@code sub}), or {@code null} when there is no token or its subject
     * is not an account id. Callers that must not fail the request over it use this; controllers want
     * {@link #requireId()}, which 401s instead. Pair it with {@link #isAuthenticated()} when the two
     * null causes ("nobody is signed in" vs "signed in but unresolvable") mean different things.
     */
    @Nullable
    public static Long idOrNull() {
        Jwt jwt = jwtOrNull();
        if (jwt == null) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The jti of the current token, for revocation on logout / refresh. */
    public static UUID requireJti() {
        Jwt jwt = requireJwt();
        String id = jwt.getId();
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token has no jti");
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token jti is malformed", e);
        }
    }

    /** The {@code preferred_username} claim (the account's login), or null if absent. */
    @Nullable
    public static String preferredUsernameOrNull() {
        Jwt jwt = jwtOrNull();
        return jwt == null ? null : jwt.getClaimAsString("preferred_username");
    }

    /** Roles from the JWT's flat {@code roles} claim; empty if absent. */
    public static List<String> roles() {
        Jwt jwt = jwtOrNull();
        if (jwt == null) {
            return List.of();
        }
        if (jwt.getClaims().get("roles") instanceof List<?> roles) {
            return roles.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    /** Impersonator account id if this is an impersonation session, else null. */
    @Nullable
    public static Long impersonatorId() {
        Jwt jwt = jwtOrNull();
        if (jwt == null || !jwt.hasClaim("act")) {
            return null;
        }
        Object act = jwt.getClaim("act");
        if (act instanceof Map<?, ?> map && map.get("sub") instanceof String sub) {
            try {
                return Long.parseLong(sub);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Absolute impersonation expiry ({@code imp_exp} claim, epoch seconds) if present, else null.
     * Carries the time-box ceiling so {@code refresh} can auto-exit once it passes — the per-token
     * {@code exp} only bounds a single token, not the whole impersonation session.
     */
    @Nullable
    public static Instant impersonationExpiresAt() {
        Jwt jwt = jwtOrNull();
        if (jwt == null) {
            return null;
        }
        Object impExp = jwt.getClaim("imp_exp");
        if (impExp instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        return null;
    }

    /**
     * The current access token's expiry as epoch seconds ({@code exp}), or null if absent. Exposed on
     * {@code /user} so the SPA can schedule a proactive, before-expiry refresh (the BFF pattern) instead
     * of discovering the expiry only when a request 401s.
     */
    @Nullable
    public static Long accessTokenExpiresAt() {
        Jwt jwt = jwtOrNull();
        if (jwt == null || jwt.getExpiresAt() == null) {
            return null;
        }
        return jwt.getExpiresAt().getEpochSecond();
    }

    /**
     * Absolute session ceiling ({@code session_exp} claim, epoch seconds) if present, else null. Set at
     * login and carried unchanged through refreshes so the rolling renewal cannot extend a session past
     * it (OWASP absolute timeout). {@code AuthSessionService.refresh} reads it to re-cap the new token.
     */
    @Nullable
    public static Instant sessionExpiresAt() {
        Jwt jwt = jwtOrNull();
        if (jwt == null) {
            return null;
        }
        Object sessionExp = jwt.getClaim("session_exp");
        if (sessionExp instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        return null;
    }

    /**
     * When the account last completed an interactive sign-in ({@code auth_time}), or {@code null} when
     * the token predates the claim. Absence is not authority: a caller with no {@code auth_time} never
     * satisfies a recent-sign-in requirement.
     */
    @Nullable
    public static Instant authTime() {
        Jwt jwt = jwtOrNull();
        if (jwt == null) {
            return null;
        }
        Object authTime = jwt.getClaim("auth_time");
        return authTime instanceof Number seconds ? Instant.ofEpochSecond(seconds.longValue()) : null;
    }

    private static Jwt requireJwt() {
        Jwt jwt = jwtOrNull();
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        return jwt;
    }

    @Nullable
    private static Jwt jwtOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return token.getToken();
        }
        return null;
    }
}
