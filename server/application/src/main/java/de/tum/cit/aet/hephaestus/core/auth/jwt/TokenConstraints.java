package de.tum.cit.aet.hephaestus.core.auth.jwt;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Absolute session and interactive sign-in deadlines, preserved unchanged through token rotation, and
 * the native app session a token belongs to.
 *
 * @param sessionExpiresAt absolute session ceiling ({@code session_exp}).
 * @param authTime         when the account last completed an interactive sign-in ({@code auth_time}).
 * @param nativeSessionId  the native app session the token belongs to ({@code sid}); absent for browser
 *                         sessions, which only the cookie refresh may rotate.
 */
public record TokenConstraints(
        @Nullable Instant sessionExpiresAt, @Nullable Instant authTime, @Nullable UUID nativeSessionId) {
    public static TokenConstraints session(@Nullable Instant sessionExpiresAt, @Nullable Instant authTime) {
        return new TokenConstraints(sessionExpiresAt, authTime, null);
    }

    /** A native app session: the browser-session deadlines plus the {@code sid} its refresh secret rotates. */
    public static TokenConstraints nativeSession(UUID nativeSessionId, Instant sessionExpiresAt, Instant authTime) {
        return new TokenConstraints(sessionExpiresAt, authTime, nativeSessionId);
    }
}
