package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class MentorPiEntriesTest extends BaseUnitTest {

    @Test
    void shouldClassifyEachPiEntryWhenTheHostReportsIt() {
        assertThat(MentorPiEntries.kind(MentorLedgerFixtures.user("a", "Hi"))).isEqualTo(MentorEntry.Kind.USER);
        assertThat(MentorPiEntries.kind(MentorLedgerFixtures.answer("b", "Hello")))
                .isEqualTo(MentorEntry.Kind.HEPH);
        assertThat(MentorPiEntries.kind(MentorLedgerFixtures.toolResult("c", "call", "fetch_context", "x", false)))
                .isEqualTo(MentorEntry.Kind.TOOL_RESULT);
        assertThat(MentorPiEntries.kind(MentorLedgerFixtures.compaction("d", "Summary", "a")))
                .isEqualTo(MentorEntry.Kind.SUMMARY);
        assertThat(MentorPiEntries.kind(MentorLedgerFixtures.thinkingLevel("e")))
                .isEqualTo(MentorEntry.Kind.SYSTEM);
        assertThat(MentorPiEntries.kind(
                        MentorLedgerFixtures.message("f", "system", JsonNodeFactory.instance.stringNode(""))))
                .isEqualTo(MentorEntry.Kind.SYSTEM);
    }

    @Test
    void shouldReadOnlyTextBlocksWhenAMessageAlsoCallsTools() {
        var call = MentorLedgerFixtures.toolCall(
                "a",
                "call",
                "fetch_context",
                JsonNodeFactory.instance.objectNode().put("path", "secret"));
        assertThat(MentorPiEntries.text(call)).isEmpty();
        assertThat(MentorPiEntries.text(MentorLedgerFixtures.toolResult("d", "call", "fetch_context", "x", false)))
                .isEmpty();
        assertThat(MentorPiEntries.text(MentorLedgerFixtures.user("b", "Plain")))
                .isEqualTo("Plain");
        assertThat(MentorPiEntries.text(MentorLedgerFixtures.compaction("c", "Summary", "a")))
                .isEqualTo("Summary");
    }

    @Test
    void shouldRenderTheLocalTimeZoneAndSurfaceWhenAQuestionIsPrefixed() {
        Instant createdAt = Instant.parse("2026-10-08T07:12:00Z");

        assertThat(MentorContextPathBuilder.prefix(createdAt, MentorEntry.Surface.SLACK, ZoneId.of("Europe/Berlin")))
                .isEqualTo("[Thu 2026-10-08 09:12 Europe/Berlin · Slack]");
        assertThat(MentorContextPathBuilder.prefix(createdAt, MentorEntry.Surface.APP, ZoneId.of("UTC")))
                .isEqualTo("[Thu 2026-10-08 07:12 UTC · app]");
    }
}
