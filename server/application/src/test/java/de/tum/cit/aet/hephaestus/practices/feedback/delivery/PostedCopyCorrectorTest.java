package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.JsonNodeFactory;

@Tag("unit")
class PostedCopyCorrectorTest {
    static final ActionAttempt OWNER = new ActionAttempt(
            UUID.randomUUID(),
            1L,
            ActionKind.RETRACT_FEEDBACK,
            "feedback:" + UUID.randomUUID() + ":1:RETRACT",
            JsonNodeFactory.instance.objectNode(),
            1,
            1,
            false,
            null,
            () -> true,
            null);

    @Test
    void shouldUpdateOnlyTheRecordedSummaryHandleAndRetainItsMarker() {
        var visibility = mock(FeedbackVisibilityService.class);
        var writer = mock(SummaryWriter.class);
        var transactions = mock(FeedbackSendTransactions.class);
        var target = mock(FeedbackTarget.class);
        var integration = mock(IntegrationRef.class);
        when(target.integration()).thenReturn(integration);
        when(integration.kind()).thenReturn(IntegrationKind.GITLAB);
        when(writer.kind()).thenReturn(IntegrationKind.GITLAB);
        UUID id = UUID.randomUUID(), copyId = UUID.randomUUID();
        var feedback = Feedback.builder()
                .id(id)
                .workspaceId(1)
                .developerId(2)
                .body("Original guidance.")
                .build();
        var copy = FeedbackCopy.builder()
                .id(copyId)
                .feedbackId(id)
                .workspaceId(1)
                .kind(FeedbackCopyKind.SUMMARY)
                .state(FeedbackCopyState.POSTED)
                .externalRef("note-17")
                .writeKey(copyId.toString())
                .writeMarker(FeedbackIds.writeMarker(copyId))
                .build();
        var repository = mock(FeedbackCopyRepository.class);
        when(repository.incorrectCopyIds(1, id)).thenReturn(Set.of(copyId));
        when(transactions.admitCorrection(eq(1L), eq(id), eq(OWNER), anyBoolean()))
                .thenReturn(FeedbackSendTransactions.CorrectionAdmission.ADMITTED);
        when(writer.updateSummary(eq(target), any(), any()))
                .thenReturn(new CopyUpdateReceipt(copyId, CopyUpdateKind.EDITED, "note-17", null, null));
        new PostedCopyCorrector(repository, visibility, List.of(writer), List.of(), transactions)
                .correct(
                        new FeedbackSendTransactions.Attempt(feedback, FeedbackSendAction.RETRACT, List.of(copy), 1),
                        target,
                        OWNER);
        var content = ArgumentCaptor.forClass(SummaryContent.class);
        verify(writer).updateSummary(eq(target), eq(new SummaryHandle("note-17", null)), content.capture());
        assertThat(content.getValue().writeMarker()).isEqualTo(copy.getWriteMarker());
        assertThat(content.getValue().body()).doesNotContain("Original guidance.");
        verify(writer, never()).postSummary(any(), any());
        verify(transactions).correction(1, id, OWNER, copyId, FeedbackCopyState.RETRACTED.name());
        assertThat(copy.getBody()).isNull();
        assertThat(feedback.getBody()).isEqualTo("Original guidance.");
    }

    @Test
    void shouldRestoreRightAfterItsAdmissionWithNoFurtherTransactionBetween() {
        var visibility = mock(FeedbackVisibilityService.class);
        var writer = mock(SummaryWriter.class);
        var transactions = mock(FeedbackSendTransactions.class);
        var target = mock(FeedbackTarget.class);
        var integration = mock(IntegrationRef.class);
        when(target.integration()).thenReturn(integration);
        when(integration.kind()).thenReturn(IntegrationKind.GITLAB);
        when(writer.kind()).thenReturn(IntegrationKind.GITLAB);
        UUID id = UUID.randomUUID(), copyId = UUID.randomUUID();
        var feedback = Feedback.builder()
                .id(id)
                .workspaceId(1)
                .developerId(2)
                .body("Original guidance.")
                .build();
        var copy = FeedbackCopy.builder()
                .id(copyId)
                .feedbackId(id)
                .workspaceId(1)
                .kind(FeedbackCopyKind.SUMMARY)
                .state(FeedbackCopyState.RETRACTED)
                .externalRef("note-17")
                .writeKey(copyId.toString())
                .writeMarker(FeedbackIds.writeMarker(copyId))
                .build();
        when(transactions.admitCorrection(1L, id, OWNER, false))
                .thenReturn(FeedbackSendTransactions.CorrectionAdmission.ADMITTED);
        when(writer.updateSummary(eq(target), any(), any()))
                .thenReturn(new CopyUpdateReceipt(copyId, CopyUpdateKind.EDITED, "note-17", null, null));

        new PostedCopyCorrector(
                        mock(FeedbackCopyRepository.class), visibility, List.of(writer), List.of(), transactions)
                .correct(
                        new FeedbackSendTransactions.Attempt(feedback, FeedbackSendAction.RETRACT, List.of(copy), 1),
                        target,
                        OWNER);

        // No transaction runs between the send boundary and the provider call, so none can wait for a lock there.
        var order = inOrder(transactions, writer);
        order.verify(transactions).admitCorrection(1L, id, OWNER, false);
        order.verify(writer).updateSummary(eq(target), any(), any());
        order.verify(transactions).correction(1, id, OWNER, copyId, FeedbackCopyState.POSTED.name());
        verifyNoMoreInteractions(transactions);
    }

    @Test
    void shouldKeepEveryRestoreForALaterAttemptWhileARemovalIsUnconfirmed() {
        var writer = mock(SummaryWriter.class);
        var transactions = mock(FeedbackSendTransactions.class);
        var target = mock(FeedbackTarget.class);
        var integration = mock(IntegrationRef.class);
        when(target.integration()).thenReturn(integration);
        when(integration.kind()).thenReturn(IntegrationKind.GITLAB);
        when(writer.kind()).thenReturn(IntegrationKind.GITLAB);
        UUID id = UUID.randomUUID(), wrong = UUID.randomUUID(), right = UUID.randomUUID();
        var feedback = Feedback.builder()
                .id(id)
                .workspaceId(1)
                .developerId(2)
                .body("Original guidance.")
                .build();
        var posted = summary(id, wrong, FeedbackCopyState.POSTED, "note-1");
        var retracted = summary(id, right, FeedbackCopyState.RETRACTED, "note-2");
        var repository = mock(FeedbackCopyRepository.class);
        when(repository.incorrectCopyIds(1, id)).thenReturn(Set.of(wrong));
        when(transactions.admitCorrection(anyLong(), any(), any(), anyBoolean()))
                .thenReturn(FeedbackSendTransactions.CorrectionAdmission.ADMITTED);
        // The provider does not confirm the removal yet.
        when(writer.updateSummary(eq(target), any(), any()))
                .thenReturn(new CopyUpdateReceipt(wrong, CopyUpdateKind.TRANSIENT, "note-1", null, null));

        new PostedCopyCorrector(
                        repository, mock(FeedbackVisibilityService.class), List.of(writer), List.of(), transactions)
                .correct(
                        new FeedbackSendTransactions.Attempt(
                                feedback, FeedbackSendAction.RETRACT, List.of(posted, retracted), 1),
                        target,
                        OWNER);

        // No restore reaches the send boundary, so its refusal cannot end the action before the removal settles.
        verify(transactions).admitCorrection(1L, id, OWNER, true);
        verify(transactions, never()).admitCorrection(1L, id, OWNER, false);
        verify(writer).updateSummary(eq(target), eq(new SummaryHandle("note-1", null)), any());
    }

    private static FeedbackCopy summary(UUID feedbackId, UUID copyId, FeedbackCopyState state, String ref) {
        return FeedbackCopy.builder()
                .id(copyId)
                .feedbackId(feedbackId)
                .workspaceId(1)
                .kind(FeedbackCopyKind.SUMMARY)
                .state(state)
                .externalRef(ref)
                .writeKey(copyId.toString())
                .writeMarker(FeedbackIds.writeMarker(copyId))
                .build();
    }

    @Test
    void shouldRequireCurrentPolicyBeforeRestoringAnExistingSummary() {
        var visibility = mock(FeedbackVisibilityService.class);
        var writer = mock(SummaryWriter.class);
        var tx = mock(FeedbackSendTransactions.class);
        when(tx.admitCorrection(anyLong(), any(), any(), anyBoolean()))
                .thenReturn(FeedbackSendTransactions.CorrectionAdmission.NOT_DUE);
        UUID id = UUID.randomUUID();
        var parent = Feedback.builder().id(id).workspaceId(1).build();
        new PostedCopyCorrector(mock(FeedbackCopyRepository.class), visibility, List.of(writer), List.of(), tx)
                .correct(
                        new FeedbackSendTransactions.Attempt(
                                parent,
                                FeedbackSendAction.RETRACT,
                                List.of(FeedbackCopy.builder()
                                        .kind(FeedbackCopyKind.SUMMARY)
                                        .state(FeedbackCopyState.RETRACTED)
                                        .externalRef("1")
                                        .build()),
                                1),
                        mock(FeedbackTarget.class),
                        OWNER);
        verify(writer, never()).updateSummary(any(), any(), any());
        verify(writer, never()).postSummary(any(), any());
    }
}
