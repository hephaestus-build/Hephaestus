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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Review probe for #5160. The 0.x upgrade wrote each delivered conversation receipt with its words (changelog
 * 1791486276211), then cleared its answer binding and dropped the chat tables (changelog 1792397538973). The copy is the
 * only home of those words, and no redaction touched them.
 */
class UpgradedConversationReceiptProbeIntegrationTest extends AbstractFeedbackIntegrationTest {
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
        jdbc.update(
                "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,body,state,created_at,posted_at)"
                        + " VALUES (?,?,?,'CHAT_TURN',0,?,'POSTED',?,?)",
                FeedbackIds.copy(row.getId(), FeedbackCopyKind.CHAT_TURN, 0),
                workspace.getId(),
                row.getId(),
                WORDS,
                Timestamp.from(AT),
                Timestamp.from(AT));

        var soft = new SoftAssertions();
        soft.assertThat(history.forDeveloper(workspace.getId(), developerId, AT.minusSeconds(1), 10).stream()
                        .filter(entry -> entry.feedbackId().equals(row.getId()))
                        .map(entry -> Objects.toString(entry.body(), "")))
                .as("the developer's feedback history")
                .containsExactly(WORDS);
        soft.assertThat(exportedWords(row)).as("the self-service export").containsExactly(WORDS);
        soft.assertAll();
    }

    private List<String> exportedWords(Feedback row) {
        var act = PersonActFixture.self(developerId);
        long id = Objects.requireNonNull(exports.requestExport(act).getId());
        await().atMost(Duration.ofSeconds(30))
                .until(() -> exports.downloadPayload(id, act).isPresent());
        var bundle = mapper.readTree(new String(
                exports.downloadPayload(id, act).orElseThrow(), StandardCharsets.UTF_8));
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
