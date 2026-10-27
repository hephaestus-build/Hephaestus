package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One occasion on an item. It carries the exact evidence version, or, when the source cannot pin one yet, no version
 * and a pending fence: an opaque value the source composes from the state it stored at the occasion. The source pins
 * the version later only while its fence is unchanged; core only stores and compares it.
 *
 * @param evidence the source's own JSON record of the occasion, retained with it; core reads only its
 *     {@code participants}, with each mirror user id and exact native identity, to find it when a person is erased
 * @param sourceKey the provider event's own identity; a second occasion of this name with the same key is the same
 *     event delivered again and records nothing
 */
public record ItemEventInput(
        long workspaceId,
        long itemId,
        ItemEventName name,
        @Nullable String itemVersion,
        @Nullable String pendingFence,
        Instant occurredAt,
        FoundBy foundBy,
        @Nullable Long actorUserId,
        @Nullable Long requestedById,
        @Nullable String evidence,
        @Nullable String sourceKey) {
    /** The largest retained evidence, as UTF-8. A source does not retain a larger record and never truncates one. */
    public static final int MAX_EVIDENCE_BYTES = 1024 * 1024;

    public ItemEventInput(
            long workspaceId,
            long itemId,
            ItemEventName name,
            @Nullable String itemVersion,
            @Nullable String pendingFence,
            Instant occurredAt,
            FoundBy foundBy,
            @Nullable Long actorUserId,
            @Nullable Long requestedById) {
        this(
                workspaceId,
                itemId,
                name,
                itemVersion,
                pendingFence,
                occurredAt,
                foundBy,
                actorUserId,
                requestedById,
                null,
                null);
    }

    public ItemEventInput(
            long workspaceId,
            long itemId,
            ItemEventName name,
            @Nullable String itemVersion,
            @Nullable String pendingFence,
            Instant occurredAt,
            FoundBy foundBy,
            @Nullable Long actorUserId,
            @Nullable Long requestedById,
            @Nullable String evidence) {
        this(
                workspaceId,
                itemId,
                name,
                itemVersion,
                pendingFence,
                occurredAt,
                foundBy,
                actorUserId,
                requestedById,
                evidence,
                null);
    }

    public ItemEventInput(
            long workspaceId,
            long itemId,
            ItemEventName name,
            String itemVersion,
            Instant occurredAt,
            FoundBy foundBy,
            @Nullable Long actorUserId,
            @Nullable Long requestedById) {
        this(workspaceId, itemId, name, itemVersion, null, occurredAt, foundBy, actorUserId, requestedById, null, null);
    }

    public ItemEventInput {
        if ((foundBy != FoundBy.WEBHOOK && foundBy != FoundBy.SYNC)
                || workspaceId <= 0
                || itemId <= 0
                || (itemVersion == null) == (pendingFence == null)
                || (itemVersion != null && (itemVersion.isBlank() || itemVersion.length() > 1024))
                || (pendingFence != null && (pendingFence.isBlank() || pendingFence.length() > 1024))
                || (actorUserId != null && actorUserId <= 0)
                || (requestedById != null && requestedById <= 0)
                || (evidence != null && !fits(evidence))
                || (sourceKey != null && (sourceKey.isBlank() || sourceKey.length() > 512)))
            throw new IllegalArgumentException("Invalid item event");
    }

    /** Whether {@code evidence} is a record core can retain: not blank and at most {@link #MAX_EVIDENCE_BYTES}. */
    public static boolean fits(String evidence) {
        return !evidence.isBlank() && evidence.getBytes(StandardCharsets.UTF_8).length <= MAX_EVIDENCE_BYTES;
    }

    /**
     * The evidence of an occasion whose record is not kept, stating why: it holds no words and names no person. Core
     * writes it when evidence expires, is erased or its item is removed; a source writes it when it could not keep the
     * record, for example {@code SIZE_LIMIT}.
     */
    public static String unretained(String reason) {
        if (!reason.matches("[A-Z][A-Z_]{0,47}")) throw new IllegalArgumentException("Invalid unretained reason");
        return "{\"" + UNRETAINED + "\":\"" + reason + "\"}";
    }

    /** The one key of {@link #unretained} evidence. */
    public static final String UNRETAINED = "unretained";

    /** The reasons core states: the record passed its expiry, was erased, or its item left the source. */
    public static final String EXPIRED = "EXPIRED";

    public static final String ERASED = "ERASED";
    public static final String REMOVED = "REMOVED";
}
