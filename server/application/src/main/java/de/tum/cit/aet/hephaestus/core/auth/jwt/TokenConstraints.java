package de.tum.cit.aet.hephaestus.core.auth.jwt;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Absolute session and interactive sign-in deadlines, preserved unchanged through token rotation.
 *
 * @param sessionId the installed-client session the token belongs to ({@code sid}); absent for browser
 *                  sessions, which only the cookie refresh may rotate
 */
public record TokenConstraints(
        @Nullable Instant sessionExpiresAt,
        @Nullable Instant authTime,
        @Nullable UUID sessionId) {
    public static TokenConstraints session(@Nullable Instant sessionExpiresAt, @Nullable Instant authTime) {
        return new TokenConstraints(sessionExpiresAt, authTime, null);
    }

    /** An installed-client session: the browser-session deadlines plus the {@code sid} its refresh secret rotates. */
    public static TokenConstraints clientSession(UUID sessionId, Instant sessionExpiresAt, Instant authTime) {
        return new TokenConstraints(sessionExpiresAt, authTime, sessionId);
    }
}
