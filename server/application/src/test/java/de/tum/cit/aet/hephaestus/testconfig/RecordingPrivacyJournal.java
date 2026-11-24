package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.privacy.spi.NativeId;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A privacy journal that stores nothing and keeps each fact that a producer records, for a test to read. It refuses a
 * ref that could carry text, so every producer test that reads it also proves that the producer records ids and states
 * only.
 */
public final class RecordingPrivacyJournal implements PrivacyJournal {
    private final List<Fact> facts = new ArrayList<>();

    @Override
    public void record(PrivacyJournalKind kind, Map<String, Object> refs) {
        refs.forEach((key, value) -> {
            if (!isIdOrState(value)) {
                throw new IllegalArgumentException("The ref " + key + " is not an id or a state");
            }
        });
        facts.add(new Fact(kind, Map.copyOf(refs)));
    }

    private static boolean isIdOrState(Object value) {
        if (value instanceof Collection<?> values) {
            return values.stream().allMatch(RecordingPrivacyJournal::isIdOrState);
        }
        return value instanceof Number
                || value instanceof Boolean
                || value instanceof UUID
                || value instanceof Enum<?>
                || value instanceof NativeId
                || value instanceof PersonIdentity;
    }

    public List<Fact> facts() {
        return List.copyOf(facts);
    }

    public record Fact(PrivacyJournalKind kind, Map<String, Object> refs) {}
}
