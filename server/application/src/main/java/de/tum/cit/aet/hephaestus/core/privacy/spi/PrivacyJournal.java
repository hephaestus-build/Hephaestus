package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Map;

/**
 * Records a privacy fact, so that the recovery after a database restore can apply it again. A producer calls it in the
 * transaction of the fact's cause, so the fact commits or rolls back with it.
 *
 * <p>Facts are delivered at least once, and replaying a fact is idempotent. A duplicate fact, or a fact whose erasure
 * found nothing, is harmless: each recovery step erases again only what is still there.
 */
public interface PrivacyJournal {

    /**
     * Records one fact of a kind.
     *
     * @param refs the identifiers and states that the fact names, by name, such as {@code workspaceId}: numbers,
     *     booleans, UUIDs, enum constants, {@link NativeId}s, {@link PersonIdentity}s or collections of these; never
     *     text
     */
    void record(PrivacyJournalKind kind, Map<String, Object> refs);
}
