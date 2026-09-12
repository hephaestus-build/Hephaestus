package de.tum.cit.aet.hephaestus.core.auth.jwt;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Absolute session and interactive sign-in deadlines, preserved unchanged through token rotation. */
public record TokenConstraints(
        @Nullable Instant sessionExpiresAt, @Nullable Instant authTime) {
    public static TokenConstraints session(@Nullable Instant sessionExpiresAt, @Nullable Instant authTime) {
        return new TokenConstraints(sessionExpiresAt, authTime);
    }
}
