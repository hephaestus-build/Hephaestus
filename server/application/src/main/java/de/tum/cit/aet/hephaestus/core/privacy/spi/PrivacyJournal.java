package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Records a privacy fact, so that the recovery after a database restore can apply it again. A producer calls it in the
 * transaction of the fact's cause, so the fact commits or rolls back with it.
 *
 * <p>Facts are delivered at least once, and replaying a fact is idempotent. A duplicate fact, or a fact whose erasure
 * found nothing, is harmless: each recovery step erases again only what is still there.
 */
@FunctionalInterface
public interface PrivacyJournal {

    /**
     * Records one fact of a kind.
     *
     * @param refs the identifiers and states that the fact names, by name, such as {@code workspaceId}: numbers,
     *     booleans, UUIDs, enum constants, {@link NativeId}s, {@link PersonIdentity}s or collections of these; never
     *     text
     */
    void record(PrivacyJournalKind kind, Map<String, Object> refs);

    /** Records a fact with the durable identifier of its cause. */
    default void record(UUID entryId, PrivacyJournalKind kind, Map<String, Object> refs) {
        record(kind, refs);
    }

    /** Records a fact whose direct confirmation is durably queued by the same transaction. */
    default void recordDeferred(UUID entryId, PrivacyJournalKind kind, Map<String, Object> refs) {
        record(entryId, kind, refs);
    }

    /** Confirms a queued fact without keeping a transaction open during the object write. */
    default void confirmStored(UUID entryId) {}

    /** Confirms an earlier fact before acknowledging a repeated withdrawal of the same authority. */
    default void confirmPending(PrivacyJournalKind kind, Map<String, Object> subject) {}

    /** Finds a completed cause by its receipt, or by its exact target when the receipt is absent. */
    default boolean confirmIfRecorded(@Nullable UUID receipt, PrivacyJournalKind kind, Map<String, Object> subject) {
        return false;
    }
}
