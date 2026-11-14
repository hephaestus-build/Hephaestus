package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.WeeklyDigestTestFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

@Tag("unit")
class ConversationIdsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T08:00:00Z");

    @Test
    void shouldGiveConversationsTurnsAndEntriesTimeOrderedIdsInTheOrderTheyAreMade() {
        List<UUID> ids = new ArrayList<>();
        for (int round = 0; round < 50; round++) {
            Conversation main = Conversation.main(7, 11, NOW);
            ConversationTurn turn = new ConversationTurn(
                    main, "request-" + round, ConversationTurn.Surface.APP, null, UUID.randomUUID(), NOW);
            ConversationEntry entry = new ConversationEntry(
                    main,
                    round,
                    WeeklyDigestTestFixtures.historical("Digest", JsonNodeFactory.instance.objectNode(), "r"),
                    NOW);
            Conversation thread = Conversation.thread(main, entry.getId(), NOW);
            ids.addAll(List.of(main.getId(), turn.getId(), entry.getId(), thread.getId()));
        }

        assertThat(ids).allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
        // The canonical form orders as the bytes do, which is how the database orders the id indexes.
        assertThat(ids.stream().map(UUID::toString).toList()).isSorted().doesNotHaveDuplicates();
    }
}
