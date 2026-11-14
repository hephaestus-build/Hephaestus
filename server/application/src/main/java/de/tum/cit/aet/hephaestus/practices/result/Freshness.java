package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.UnverifiableReason;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Whether a result still describes the work, and what is known about it.
 *
 * @param unverifiableReason why the currentness is {@link Currentness#UNVERIFIABLE}; null for any other currentness
 * @param capturedAt when the result's capture read the work: the result describes the work as of that time. A later
 *     check that found the work unchanged is not recorded. Null when the record of that capture is missing.
 */
public record Freshness(
        Currentness currentness,
        @Nullable UnverifiableReason unverifiableReason,
        @Nullable Instant capturedAt) {
    public Freshness {
        Objects.requireNonNull(currentness, "currentness");
        if ((currentness == Currentness.UNVERIFIABLE) != (unverifiableReason != null))
            throw new IllegalArgumentException("Only an unverifiable result has an unverifiable reason");
        if (unverifiableReason == UnverifiableReason.RECORD_MISSING && capturedAt != null)
            throw new IllegalArgumentException("A result without its record has no verification time");
    }

    public static Freshness current(@Nullable Instant capturedAt) {
        return new Freshness(Currentness.CURRENT, null, capturedAt);
    }

    public static Freshness stale(@Nullable Instant capturedAt) {
        return new Freshness(Currentness.STALE, null, capturedAt);
    }

    public static Freshness recordMissing() {
        return new Freshness(Currentness.UNVERIFIABLE, UnverifiableReason.RECORD_MISSING, null);
    }
}
