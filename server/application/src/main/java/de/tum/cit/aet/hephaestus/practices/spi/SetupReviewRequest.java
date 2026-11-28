package de.tum.cit.aet.hephaestus.practices.spi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one command that starts a review from a review setup (ADR 0167 decision 1). The setup gives the practices, the
 * budget and the audience; the request names only the item at its version. {@link ReviewRequest} stays the internal
 * request of every review.
 *
 * @param requestKey the same key returns the same review; the same key with another input is refused
 * @param requestedBy the account that asks, for a {@code REQUEST}; a {@code SCHEDULE} review has no requester
 */
public record SetupReviewRequest(
        long workspaceId,
        UUID setupId,
        ReviewedItem item,
        ReviewOrigin origin,
        UUID requestKey,
        @Nullable Long requestedBy) {

    public SetupReviewRequest {
        if (origin != ReviewOrigin.REQUEST && origin != ReviewOrigin.SCHEDULE)
            throw new IllegalArgumentException("A setup starts a review on a request or on its schedule.");
        if ((origin == ReviewOrigin.REQUEST) != (requestedBy != null))
            throw new IllegalArgumentException("A requested review names its requester, and a scheduled one has none.");
    }

    /**
     * The key of the scheduled review of a setup that is due at {@code dueAt}, so two writers of one due time make one
     * review: a name-based UUID of version 5 (RFC 9562 § 5.5) in the setup's namespace, named by the ISO-8601 due time.
     */
    public static UUID scheduleKey(UUID setupId, Instant dueAt) {
        return nameBased(setupId, dueAt.toString());
    }

    /** The JDK makes only version 3 (MD5); version 5 is the same layout over SHA-1. */
    static UUID nameBased(UUID namespace, String name) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is a required JDK algorithm", e);
        }
        sha1.update(ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits())
                .array());
        byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        var bits = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bits.getLong(), bits.getLong());
    }
}
