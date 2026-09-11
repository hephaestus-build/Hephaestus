package de.tum.cit.aet.hephaestus.core.auth.jwt;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The authority and deadlines a token carries beyond its own {@code exp}: they survive every rotation
 * unchanged, so a rolling silent refresh can extend neither the session, the impersonation, nor the age
 * of the sign-in the step-up gate reads.
 *
 * <p>A parameter object rather than another {@code issue(…)} overload: the four values are all optional
 * and all instants, so positional overloads would be indistinguishable at the call site.
 *
 * @param impersonatorId         RFC 8693 {@code act} subject; absent when not impersonating.
 * @param impersonationExpiresAt absolute impersonation ceiling ({@code imp_exp}).
 * @param sessionExpiresAt       absolute session ceiling ({@code session_exp}, OWASP absolute timeout).
 * @param authTime               when the account last completed an interactive sign-in ({@code auth_time}).
 */
public record TokenConstraints(
        @Nullable Long impersonatorId,
        @Nullable Instant impersonationExpiresAt,
        @Nullable Instant sessionExpiresAt,
        @Nullable Instant authTime) {
    /** An ordinary session: no impersonation, so no {@code act} and no {@code imp_exp}. */
    public static TokenConstraints session(@Nullable Instant sessionExpiresAt, @Nullable Instant authTime) {
        return new TokenConstraints(null, null, sessionExpiresAt, authTime);
    }

    /** Impersonation: the session ceiling and the sign-in time stay the operator's, not the target's. */
    public static TokenConstraints impersonation(
            Long impersonatorId,
            Instant impersonationExpiresAt,
            @Nullable Instant sessionExpiresAt,
            @Nullable Instant authTime) {
        return new TokenConstraints(impersonatorId, impersonationExpiresAt, sessionExpiresAt, authTime);
    }
}
