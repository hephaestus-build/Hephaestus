package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The object copied into the recovery directory, under the configured prefix and the UTC recording date. */
record PrivacyJournalObject(int version, UUID entryId, PrivacyJournalKind kind, JsonNode refs, Instant recordedAt) {

    PrivacyJournalObject(UUID entryId, PrivacyJournalKind kind, JsonNode refs, Instant recordedAt) {
        this(1, entryId, kind, refs, recordedAt);
    }

    String relativeKey() {
        return recordedAt.atOffset(ZoneOffset.UTC).toLocalDate() + "/" + entryId + ".json";
    }
}
