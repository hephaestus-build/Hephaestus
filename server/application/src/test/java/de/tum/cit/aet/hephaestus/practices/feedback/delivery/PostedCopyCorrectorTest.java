package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.function.Supplier;
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
            () -> true);

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
        when(transactions.holdsLease(1, id, OWNER)).thenReturn(true);
        when(transactions.recipientWrite(eq(2L), any())).thenAnswer(a -> {
            Supplier<?> callback = a.getArgument(1);
            return callback.get();
        });
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
    void shouldRequireCurrentPolicyBeforeRestoringAnExistingSummary() {
        var visibility = mock(FeedbackVisibilityService.class);
        var writer = mock(SummaryWriter.class);
        var tx = mock(FeedbackSendTransactions.class);
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
