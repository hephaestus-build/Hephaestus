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
 * @param lastVerifiedAt when the result's sources were last verified against the work: the time its capture read them.
 *     Null when the record of that capture is missing.
 */
public record Freshness(
        Currentness currentness,
        @Nullable UnverifiableReason unverifiableReason,
        @Nullable Instant lastVerifiedAt) {
    public Freshness {
        Objects.requireNonNull(currentness, "currentness");
        if ((currentness == Currentness.UNVERIFIABLE) != (unverifiableReason != null))
            throw new IllegalArgumentException("Only an unverifiable result has an unverifiable reason");
        if (unverifiableReason == UnverifiableReason.RECORD_MISSING && lastVerifiedAt != null)
            throw new IllegalArgumentException("A result without its record has no verification time");
    }

    public static Freshness current(@Nullable Instant lastVerifiedAt) {
        return new Freshness(Currentness.CURRENT, null, lastVerifiedAt);
    }

    public static Freshness stale(@Nullable Instant lastVerifiedAt) {
        return new Freshness(Currentness.STALE, null, lastVerifiedAt);
    }

    public static Freshness recordMissing() {
        return new Freshness(Currentness.UNVERIFIABLE, UnverifiableReason.RECORD_MISSING, null);
    }
}
