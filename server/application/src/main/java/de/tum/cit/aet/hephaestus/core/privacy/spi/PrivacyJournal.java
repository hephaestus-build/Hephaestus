package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Map;

/**
 * Records a privacy fact, so that the recovery after a database restore can apply it again. A producer calls it in the
 * transaction of the fact's cause, so the fact commits or rolls back with it.
 */
public interface PrivacyJournal {

    /**
     * Records one fact of a kind.
     *
     * @param refs the ids that the fact names, by name, such as {@code workspaceId}; never content
     */
    void record(PrivacyJournalKind kind, Map<String, Object> refs);
}
