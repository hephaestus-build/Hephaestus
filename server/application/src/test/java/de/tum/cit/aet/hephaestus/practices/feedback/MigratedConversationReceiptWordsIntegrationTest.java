package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonActFixture;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.MirroredThreadStateQuery;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.assertj.core.api.SoftAssertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The 1.0 upgrade wrote each delivered 0.x conversation receipt with its words (changelog 1791486276211), then cleared
 * its answer binding and dropped the chat tables (changelog 1792397538973). Such a copy has no answer and no write fence,
 * so its words have no other home, and the feedback history and the self-service export show them. A receipt of this
 * release has a write fence: when an erasure deletes its answer and clears the binding, it shows no words, although the
 * copy still stores them until #5504.
 */
class MigratedConversationReceiptWordsIntegrationTest extends AbstractFeedbackIntegrationTest {
    private static final String WORDS = "Words that the upgrade kept on the receipt.";

    @Autowired
    FeedbackHistoryQueryService history;

    @Autowired
    AccountExportService exports;

    @MockitoBean
    MirroredThreadStateQuery mirroredThreads;

    @MockitoBean
    ResultHistoryQuery permission;

    @MockitoBean
    ResultSourceAuthorization publicPermission;

    @Test
    void shouldStillShowTheWordsOfAReceiptWhoseUpgradeDroppedItsAnswer() {
        var row = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        var proof = result();
        bind(row, proof);
        authorize(proof);
        receipt(row, null);

        var soft = new SoftAssertions();
        soft.assertThat(historyWords(row))
                .as("the developer's feedback history")
                .containsExactly(WORDS);
        soft.assertThat(exportedWords(row)).as("the self-service export").containsExactly(WORDS);
        soft.assertAll();
    }

    @Test
    void shouldShowNoWordsOfAReceiptOfThisReleaseWhoseAnswerWasErased() {
        var row = piece(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, AT);
        var proof = result();
        bind(row, proof);
        authorize(proof);
        // What an erasure leaves when it deletes the answer and its turn: the bindings are cleared, the write fence
        // and the stored words stay.
        receipt(row, Timestamp.from(AT.minusSeconds(5)));

        var soft = new SoftAssertions();
        soft.assertThat(historyWords(row))
                .as("the developer's feedback history")
                .containsExactly("");
        soft.assertThat(exportedWords(row)).as("the self-service export").containsExactly("");
        soft.assertAll();
    }

    private void receipt(Feedback row, @Nullable Timestamp writeStartedAt) {
        var copy = FeedbackIds.copy(row.getId(), FeedbackCopyKind.CHAT_TURN, 0);
        jdbc.update(
                "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,body,state,write_key,"
                        + "write_started_at,created_at,posted_at) VALUES (?,?,?,'CHAT_TURN',0,?,'POSTED',?,?,?,?)",
                copy,
                workspace.getId(),
                row.getId(),
                WORDS,
                writeStartedAt == null ? null : copy.toString(),
                writeStartedAt,
                Timestamp.from(AT),
                Timestamp.from(AT));
    }

    private List<String> historyWords(Feedback row) {
        return history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 10).stream()
                .filter(entry -> entry.feedbackId().equals(row.getId()))
                .map(entry -> Objects.toString(entry.body(), ""))
                .toList();
    }

    private List<String> exportedWords(Feedback row) {
        var act = PersonActFixture.self(developerId);
        long id = Objects.requireNonNull(exports.requestExport(act).getId());
        await().atMost(Duration.ofSeconds(30))
                .until(() -> exports.downloadPayload(id, act).isPresent());
        var bundle =
                mapper.readTree(new String(exports.downloadPayload(id, act).orElseThrow(), StandardCharsets.UTF_8));
        var received = bundle.path("feedbackReceived")
                .valueStream()
                .filter(entry ->
                        entry.path("feedbackId").asString().equals(row.getId().toString()))
                .toList();
        assertThat(received).as("the export holds the delivered feedback").hasSize(1);
        return received.getFirst()
                .path("pieces")
                .valueStream()
                .map(piece -> piece.path("body").asString(""))
                .toList();
    }

    private void authorize(Result proof) {
        var custody = new HistoryCustody(true, false, List.of(), List.of(), List.of());
        when(publicPermission.custody(argThat(row -> row != null && row.id().equals(proof.getId()))))
                .thenReturn(custody);
        for (var purpose :
                List.of(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY, SourceUsePurpose.CONVERSATIONAL_MENTORING))
            when(publicPermission.authorized(
                            argThat(row -> row != null && row.id().equals(proof.getId())), eq(custody), eq(purpose)))
                    .thenReturn(true);
        var retained = mock(ResultHistoryEntry.class);
        when(retained.copyAuthorityComplete()).thenReturn(true);
        when(retained.resultId()).thenReturn(proof.getId());
        when(retained.practiceSlug()).thenReturn("regression-tests");
        when(permission.find(workspace.getId(), proof.getId(), developerId, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(retained));
        when(permission.find(
                        workspace.getId(), proof.getId(), developerId, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(Optional.of(retained));
        // The history reads a page's support in one batch.
        when(permission.findAll(eq(workspace.getId()), any(), eq(developerId), any()))
                .thenReturn(Map.of(Objects.requireNonNull(proof.getId()), retained));
    }
}
