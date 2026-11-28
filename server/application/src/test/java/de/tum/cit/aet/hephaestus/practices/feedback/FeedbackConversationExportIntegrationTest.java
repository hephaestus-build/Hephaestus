package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonActFixture;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The account export reads the words of a conversation receipt from the answer that showed them, as the export binds
 * the account, not from the receipt. So a redaction of the answer, or of its show_feedback result, leaves none in a
 * later export, and expires the ready export that holds them.
 */
class FeedbackConversationExportIntegrationTest extends AbstractFeedbackIntegrationTest {
    @Autowired
    AccountExportService exports;

    @Autowired
    OwnerScope owners;

    @Autowired
    ConversationRedaction redaction;

    @Test
    void shouldExportTheAnswerWordsOnlyWhileTheAnswerStands() {
        var row = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        var shown = new ShownConversationAnswers(jdbc, transactionManager, owners).show(row, "Try a focused test.");

        var standing = export();
        assertThat(piecesOf(standing.bundle(), row)).singleElement().satisfies(piece -> {
            assertThat(piece.path("kind").asString()).isEqualTo("CHAT_TURN");
            assertThat(piece.path("body").asString()).isEqualTo("Try a focused test.");
        });

        redact(shown.answer());

        assertThat(exports.downloadPayload(standing.id(), PersonActFixture.self(developerId)))
                .as("the ready export that holds the redacted words serves them no more")
                .isEmpty();
        assertNoWordsIn(export(), row);
    }

    @Test
    void shouldExpireTheReadyExportWhenOnlyTheShowFeedbackResultIsRedacted() {
        var row = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        var shown = new ShownConversationAnswers(jdbc, transactionManager, owners).show(row, "Try a focused test.");
        var standing = export();
        assertThat(standing.bundle().toString()).contains("Try a focused test.");

        redact(shown.result());

        assertThat(exports.downloadPayload(standing.id(), PersonActFixture.self(developerId)))
                .as("the words need the show_feedback result too, so its redaction expires the ready export")
                .isEmpty();
        assertNoWordsIn(export(), row);
    }

    private void redact(UUID entry) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            owners.ofJob(developerId);
            redaction.redact(List.of(entry));
        });
    }

    private static void assertNoWordsIn(Download later, Feedback row) {
        assertThat(piecesOf(later.bundle(), row))
                .as("the receipt stays, and the export shows none of its words")
                .singleElement()
                .satisfies(piece -> {
                    assertThat(piece.path("kind").asString()).isEqualTo("CHAT_TURN");
                    assertThat(piece.hasNonNull("body")).isFalse();
                });
        assertThat(later.bundle().toString())
                .as("no copy of the redacted words leaves in the export")
                .doesNotContain("Try a focused test.");
    }

    /** A ready export and its bundle. */
    private record Download(long id, JsonNode bundle) {}

    /** A fresh export of the developer's account, as it is offered for download. */
    private Download export() {
        var act = PersonActFixture.self(developerId);
        long id = Objects.requireNonNull(exports.requestExport(act).getId());
        await().atMost(Duration.ofSeconds(30))
                .until(() -> exports.downloadPayload(id, act).isPresent());
        byte[] payload = exports.downloadPayload(id, act).orElseThrow();
        return new Download(id, mapper.readTree(new String(payload, StandardCharsets.UTF_8)));
    }

    private static List<JsonNode> piecesOf(JsonNode export, Feedback row) {
        var received = StreamSupport.stream(export.path("feedbackReceived").spliterator(), false)
                .filter(entry ->
                        entry.path("feedbackId").asString().equals(row.getId().toString()))
                .toList();
        assertThat(received).as("the export holds the delivered feedback").hasSize(1);
        return StreamSupport.stream(received.getFirst().path("pieces").spliterator(), false)
                .toList();
    }
}
