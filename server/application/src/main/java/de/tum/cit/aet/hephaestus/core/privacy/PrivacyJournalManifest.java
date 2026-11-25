package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The added entry list of an erasure recovery manifest, version 5. */
record PrivacyJournalManifest(int version, Instant restorePoint, List<Entry> entries) {

    static final int VERSION = 5;

    PrivacyJournalManifest {
        if (version != VERSION) throw new IllegalArgumentException("Expected recovery manifest version 5");
        entries = List.copyOf(entries);
    }

    /**
     * The kind selects the recovery step. Storage time precedes commit, so recovery replays all entries
     * regardless of recordedAt.
     */
    record Entry(UUID entryId, PrivacyJournalKind kind, JsonNode refs, Instant recordedAt) {}
}
