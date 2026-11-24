package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.NativeId;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Refs carry identifiers and states only, never text: the double that every producer test reads enforces it. */
class RecordingPrivacyJournalTest extends BaseUnitTest {
    private final RecordingPrivacyJournal journal = new RecordingPrivacyJournal();

    private enum Change {
        EDITED
    }

    @Test
    void shouldKeepAFactWhenItsRefsAreIdsAndStates() {
        var identity = new PersonIdentity(3L, "U123", "T123");
        UUID entryId = UUID.randomUUID();
        Map<String, Object> refs = Map.of(
                "workspaceId",
                7L,
                "entryId",
                entryId,
                "change",
                Change.EDITED,
                "channelId",
                new NativeId("C123"),
                "identities",
                List.of(identity));

        journal.record(PrivacyJournalKind.PERSON_ERASED, refs);

        assertThat(journal.facts())
                .containsExactly(new RecordingPrivacyJournal.Fact(PrivacyJournalKind.PERSON_ERASED, refs));
    }

    @Test
    void shouldRefuseAFactWhenARefIsText() {
        assertThatThrownBy(() -> journal.record(
                        PrivacyJournalKind.SLACK_MESSAGE_CHANGED, Map.of("workspaceId", 7L, "change", "EDITED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("change");
        assertThatThrownBy(() -> journal.record(
                        PrivacyJournalKind.PERSON_ERASED, Map.of("identities", List.of(7L, "Words of a message"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identities");
        assertThat(journal.facts()).isEmpty();
    }
}
