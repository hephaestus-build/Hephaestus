package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A privacy journal that stores nothing and keeps each fact that a producer records, for a test to read. */
public final class RecordingPrivacyJournal implements PrivacyJournal {
    private final List<Fact> facts = new ArrayList<>();

    @Override
    public void record(PrivacyJournalKind kind, Map<String, Object> refs) {
        facts.add(new Fact(kind, Map.copyOf(refs)));
    }

    public List<Fact> facts() {
        return List.copyOf(facts);
    }

    public record Fact(PrivacyJournalKind kind, Map<String, Object> refs) {}
}
