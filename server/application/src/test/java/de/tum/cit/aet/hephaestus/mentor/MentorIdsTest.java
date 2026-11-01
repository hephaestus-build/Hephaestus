package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

@Tag("unit")
class MentorIdsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T08:00:00Z");

    @Test
    void shouldGiveConversationsTurnsAndEntriesTimeOrderedIdsInTheOrderTheyAreMade() {
        List<UUID> ids = new ArrayList<>();
        for (int round = 0; round < 50; round++) {
            MentorConversation main = MentorConversation.main(7, 11, NOW);
            MentorTurn turn = new MentorTurn(main, "request-" + round, MentorTurn.Surface.APP, null, NOW);
            MentorEntry entry = new MentorEntry(
                    main, round, MentorEntryDraft.digest("Digest", JsonNodeFactory.instance.objectNode(), "r"), NOW);
            MentorConversation thread = MentorConversation.thread(main, entry.getId(), NOW);
            ids.addAll(List.of(main.getId(), turn.getId(), entry.getId(), thread.getId()));
        }

        assertThat(ids).allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
        // The canonical form orders as the bytes do, which is how the database orders the id indexes.
        assertThat(ids.stream().map(UUID::toString).toList()).isSorted().doesNotHaveDuplicates();
    }
}
