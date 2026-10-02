package de.tum.cit.aet.hephaestus.agent.handler.spi;

import org.jspecify.annotations.Nullable;

/**
 * Tri-state result of {@link JobTypeHandler#findExistingDelivery}. Deliberately not an
 * {@code Optional}: collapsing "confirmed absent" and "could not determine" makes every failed lookup
 * fall through to posting again.
 */
public record ExistingDeliveryLookup(
        Kind kind, @Nullable String commentId, @Nullable String commentUrl) {
    public enum Kind {
        /** A delivery for this exact job was found already posted at the provider. */
        FOUND,
        /** The channel confirmed no delivery for this job exists at the provider. */
        ABSENT,
        /** The channel could not determine either way (error, rate limit, or unsupported). */
        UNKNOWN,
    }

    public static ExistingDeliveryLookup found(String commentId) {
        return found(commentId, null);
    }

    public static ExistingDeliveryLookup found(String commentId, @Nullable String commentUrl) {
        if (commentId == null || commentId.isBlank()) {
            throw new IllegalArgumentException("FOUND outcome requires a non-blank commentId");
        }
        return new ExistingDeliveryLookup(Kind.FOUND, commentId, commentUrl);
    }

    public static ExistingDeliveryLookup absent() {
        return new ExistingDeliveryLookup(Kind.ABSENT, null, null);
    }

    public static ExistingDeliveryLookup unknown() {
        return new ExistingDeliveryLookup(Kind.UNKNOWN, null, null);
    }
}
