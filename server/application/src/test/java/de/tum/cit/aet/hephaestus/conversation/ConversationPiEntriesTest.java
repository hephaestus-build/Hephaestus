package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class ConversationPiEntriesTest extends BaseUnitTest {

    @Test
    void shouldClassifyEachPiEntryWhenTheHostReportsIt() {
        assertThat(ConversationPiEntries.kind(ConversationLedgerFixtures.user("a", "Hi")))
                .isEqualTo(ConversationEntry.Kind.USER);
        assertThat(ConversationPiEntries.kind(ConversationLedgerFixtures.answer("b", "Hello")))
                .isEqualTo(ConversationEntry.Kind.ANSWER);
        assertThat(ConversationPiEntries.kind(
                        ConversationLedgerFixtures.toolResult("c", "call", "fetch_context", "x", false)))
                .isEqualTo(ConversationEntry.Kind.TOOL_RESULT);
        assertThat(ConversationPiEntries.kind(ConversationLedgerFixtures.compaction("d", "Summary", "a")))
                .isEqualTo(ConversationEntry.Kind.SUMMARY);
        assertThat(ConversationPiEntries.kind(ConversationLedgerFixtures.thinkingLevel("e")))
                .isEqualTo(ConversationEntry.Kind.SYSTEM);
        assertThat(ConversationPiEntries.kind(
                        ConversationLedgerFixtures.message("f", "system", JsonNodeFactory.instance.stringNode(""))))
                .isEqualTo(ConversationEntry.Kind.SYSTEM);
    }

    @Test
    void shouldCountAContextFolderResultAsAPersonDataCopyWhenATurnStoresIt() {
        for (String tool : ConversationPiEntries.COPYING_TOOLS) {
            assertThat(ConversationPiEntries.copiesPersonData(
                            ConversationLedgerFixtures.toolResult("a", "call", tool, "x", false)))
                    .as(tool)
                    .isTrue();
            // The erasure step selects the same tools in SQL.
            assertThat(ConversationEntryRepository.COPIES_PERSON_DATA).contains("'" + tool + "'");
        }
        assertThat(ConversationPiEntries.copiesPersonData(
                        ConversationLedgerFixtures.toolResult("b", "call", "search_conversation", "x", false)))
                .isFalse();
    }

    @Test
    void shouldReadOnlyTextBlocksWhenAMessageAlsoCallsTools() {
        var call = ConversationLedgerFixtures.toolCall(
                "a",
                "call",
                "fetch_context",
                JsonNodeFactory.instance.objectNode().put("path", "secret"));
        assertThat(ConversationPiEntries.text(call)).isEmpty();
        assertThat(ConversationPiEntries.text(
                        ConversationLedgerFixtures.toolResult("d", "call", "fetch_context", "x", false)))
                .isEmpty();
        assertThat(ConversationPiEntries.text(ConversationLedgerFixtures.user("b", "Plain")))
                .isEqualTo("Plain");
        assertThat(ConversationPiEntries.text(ConversationLedgerFixtures.compaction("c", "Summary", "a")))
                .isEqualTo("Summary");
    }

    @Test
    void shouldRenderTheLocalTimeZoneAndSurfaceWhenAQuestionIsPrefixed() {
        Instant createdAt = Instant.parse("2026-10-08T07:12:00Z");

        assertThat(ConversationContextPathBuilder.prefix(
                        createdAt, ConversationEntry.Surface.SLACK, ZoneId.of("Europe/Berlin")))
                .isEqualTo("[Thu 2026-10-08 09:12 Europe/Berlin · Slack]");
        assertThat(ConversationContextPathBuilder.prefix(createdAt, ConversationEntry.Surface.APP, ZoneId.of("UTC")))
                .isEqualTo("[Thu 2026-10-08 07:12 UTC · app]");
    }
}
