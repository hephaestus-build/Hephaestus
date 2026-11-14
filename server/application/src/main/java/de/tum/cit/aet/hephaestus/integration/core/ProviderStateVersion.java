package de.tum.cit.aet.hephaestus.integration.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;

/** Provider versions order mutable state; arrival order cannot make a replay current. */
public final class ProviderStateVersion {
    private ProviderStateVersion() {}

    /**
     * The version as the database keeps it. A version column holds microseconds and PostgreSQL rounds a finer value,
     * so a version is compared and stored at that precision: otherwise an identical snapshot can read back as newer.
     */
    public static @Nullable Instant atStoredPrecision(@Nullable Instant version) {
        return version == null ? null : version.truncatedTo(ChronoUnit.MICROS);
    }

    /** An undated payload cannot replace a dated snapshot. */
    public static boolean isOlder(@Nullable Instant incoming, @Nullable Instant stored) {
        return stored != null && (incoming == null || incoming.isBefore(stored));
    }

    /** Equal timestamps do not prove a change. Callers may accept separately fenced enrichment. */
    public static boolean cannotReplace(@Nullable Instant incoming, @Nullable Instant stored) {
        return stored != null && (incoming == null || !incoming.isAfter(stored));
    }
    /** A read begun after the stored write can settle changes hidden by provider timestamp precision. */
    public static boolean acceptsEqualRead(
            @Nullable Instant incoming, @Nullable Instant stored, Instant observedAt, @Nullable Instant lastWriteAt) {
        return incoming != null && incoming.equals(stored) && lastWriteAt != null && observedAt.isAfter(lastWriteAt);
    }

    /**
     * Whether a snapshot must not replace the stored state: it is undated or not newer, and it is not a sync read that
     * began after the last write ({@link #acceptsEqualRead}).
     */
    public static boolean refuses(
            @Nullable Instant incoming,
            @Nullable Instant stored,
            boolean syncRead,
            Instant observedAt,
            @Nullable Instant lastWriteAt) {
        boolean freshEqualRead = syncRead && acceptsEqualRead(incoming, stored, observedAt, lastWriteAt);
        return !freshEqualRead && cannotReplace(incoming, stored);
    }
}
