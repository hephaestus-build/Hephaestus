package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyWriteResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.integration.core.spi.LookupPresence;
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackProviderReadback;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.JsonNodeFactory;

@Tag("unit")
class FeedbackSendServiceTest {

    final FeedbackSendTransactions transactions = mock(FeedbackSendTransactions.class);
    final FeedbackTargetResolver targets = mock(FeedbackTargetResolver.class);
    final SummaryWriter writer = mock(SummaryWriter.class);
    final FeedbackGate gate = mock(FeedbackGate.class);
    final UUID id = UUID.randomUUID();
    final UUID copyId = UUID.randomUUID();
    final ActionAttempt owner = new ActionAttempt(
            UUID.randomUUID(),
            1L,
            ActionKind.DELIVER_FEEDBACK,
            "feedback:" + id + ":1:POST",
            JsonNodeFactory.instance.objectNode(),
            1,
            1,
            false,
            null,
            () -> true);
    final Feedback feedback = Feedback.builder()
            .id(id)
            .workspaceId(1)
            .developerId(2)
            .body("Add a focused test.")
            .build();
    final ItemRef item = mock(ItemRef.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    FeedbackTarget target;
    FeedbackSendService sender;

    @BeforeEach
    void setup() {
        target = mock(FeedbackTarget.class);
        when(item.kind()).thenReturn(new ItemKind("scm.issue"));
        when(target.item()).thenReturn(item);
        var integration = mock(IntegrationRef.class);
        when(integration.kind()).thenReturn(IntegrationKind.GITHUB);
        when(target.integration()).thenReturn(integration);
        when(writer.kind()).thenReturn(IntegrationKind.GITHUB);
        when(targets.resolve(feedback)).thenReturn(target);
        when(targets.resolveExisting(feedback)).thenReturn(target);
        sender = new FeedbackSendService(
                transactions,
                targets,
                List.of(writer),
                List.of(),
                mock(PostedCopyCorrector.class),
                new FeedbackReadbackMeters(meters),
                mock(FeedbackProviderReadback.class),
                mock(FeedbackOwnCopyEditor.class),
                gate);
    }

    FeedbackCopy copy(FeedbackCopyState state) {
        return FeedbackCopy.builder()
                .id(copyId)
                .workspaceId(1)
                .feedbackId(id)
                .kind(FeedbackCopyKind.SUMMARY)
                .state(state)
                .writeStartedAt(
                        state == FeedbackCopyState.WRITE_STARTED || state == FeedbackCopyState.POSTED
                                ? Instant.EPOCH
                                : null)
                .writeKey(copyId.toString())
                .writeMarker(FeedbackIds.writeMarker(copyId))
                .build();
    }

    void attempt(FeedbackCopyState state) {
        when(transactions.claim(eq(1L), eq(id), eq(owner)))
                .thenReturn(new FeedbackSendTransactions.Attempt(
                        feedback, FeedbackSendAction.POST, List.of(copy(state)), 1));
    }

    @Test
    void shouldSyncTheSourcesBeforeTheClaimSoTheLockedCheckReadsACurrentMirror() {
        attempt(FeedbackCopyState.PLANNED);

        sender.send(owner);

        var order = inOrder(gate, transactions);
        order.verify(gate).syncSources(1L, id);
        order.verify(transactions).claim(eq(1L), eq(id), eq(owner));
    }

    @Test
    void shouldLeaveSourcesThatCannotBeSyncedToTheOutboxRetryInsteadOfWithholding() {
        var unreachable = new IllegalStateException("The provider cannot be reached");
        doThrow(unreachable).when(gate).syncSources(1L, id);

        assertThatThrownBy(() -> sender.send(owner)).isSameAs(unreachable);
        verify(transactions, never()).claim(anyLong(), any(), any());
    }

    @Test
    void shouldCommitTheCopyFenceBeforeAProviderCreate() {
        attempt(FeedbackCopyState.PLANNED);
        when(transactions.fence(eq(1L), eq(id), eq(owner), eq(List.of(copyId)), eq(List.of())))
                .thenReturn(true);
        when(writer.postSummary(eq(target), any())).thenReturn(new SummaryHandle("42", "https://example.test/copy/42"));
        sender.send(owner);
        var order = inOrder(transactions, writer);
        order.verify(transactions).fence(eq(1L), eq(id), eq(owner), eq(List.of(copyId)), eq(List.of()));
        order.verify(writer).postSummary(eq(target), any());
        order.verify(transactions).receipt(eq(1L), eq(id), eq(owner), anyList());
    }

    @Test
    void shouldNeverCreateAgainWhenVisibilityIsUnknown() {
        attempt(FeedbackCopyState.WRITE_STARTED);
        when(writer.findExistingSummary(eq(target), any())).thenReturn(new SummaryLookup(LookupPresence.UNKNOWN, null));
        sender.send(owner);
        verify(writer, never()).postSummary(any(), any());
        verify(transactions, never()).absent(anyLong(), any(), eq(owner), any());
        assertThat(readbacks("unknown")).isEqualTo(1);
    }

    @Test
    void shouldRecordProvedAbsenceWithoutAutomaticallyReposting() {
        attempt(FeedbackCopyState.WRITE_STARTED);
        when(writer.findExistingSummary(eq(target), any())).thenReturn(new SummaryLookup(LookupPresence.ABSENT, null));
        sender.send(owner);
        verify(transactions).absent(eq(1L), eq(id), eq(owner), eq(copyId));
        verify(writer, never()).postSummary(any(), any());
        assertThat(readbacks("absent")).isEqualTo(1);
    }

    @Test
    void shouldReconcileAnExistingReceiptAfterACrashWithoutAnotherCreate() {
        attempt(FeedbackCopyState.WRITE_STARTED);
        when(writer.findExistingSummary(eq(target), any()))
                .thenReturn(new SummaryLookup(
                        LookupPresence.FOUND, new SummaryHandle("42", "https://example.test/copy/42")));
        sender.send(owner);
        ArgumentCaptor<List<CopyReceipt>> capture = ArgumentCaptor.captor();
        verify(transactions).receipt(eq(1L), eq(id), eq(owner), capture.capture());
        assertThat(capture.getValue().getFirst().externalRef()).isEqualTo("42");
        verify(writer, never()).postSummary(any(), any());
        assertThat(readbacks("found")).isEqualTo(1);
    }

    @Test
    void shouldNeverPostAConfirmedCopyAgain() {
        attempt(FeedbackCopyState.POSTED);
        sender.send(owner);
        verify(writer, never()).postSummary(any(), any());
        verify(writer, never()).findExistingSummary(any(), any());
        assertThat(readbacks("found") + readbacks("absent") + readbacks("unknown"))
                .isZero();
    }

    @Test
    void shouldStopBeforeProviderCreateWhenTheFenceRefuses() {
        attempt(FeedbackCopyState.PLANNED);
        sender.send(owner);
        verify(writer, never()).postSummary(any(), any());
    }

    @Test
    void shouldRetainUnknownVisibilityAfterAPostThrows() {
        attempt(FeedbackCopyState.PLANNED);
        when(transactions.fence(eq(1L), eq(id), eq(owner), anyList(), anyList()))
                .thenReturn(true);
        when(writer.postSummary(eq(target), any())).thenThrow(new IllegalStateException("connection ended"));
        sender.send(owner);
        verify(transactions).error(eq(1L), eq(id), eq(owner));
        verify(transactions).finish(1, id, owner);
    }

    final LineNoteWriter notes = mock(LineNoteWriter.class);
    final UUID noteId = UUID.randomUUID();

    FeedbackCopy note(FeedbackCopyState state) {
        return FeedbackCopy.builder()
                .id(noteId)
                .workspaceId(1)
                .feedbackId(id)
                .kind(FeedbackCopyKind.LINE_NOTE)
                .position(1)
                .body("Name the failure you catch.")
                .path("src/App.java")
                .startLine(8)
                .endLine(8)
                .side(CitationSide.NEW)
                .state(state)
                .writeStartedAt(state == FeedbackCopyState.PLANNED ? null : Instant.EPOCH)
                .writeKey(noteId.toString())
                .writeMarker(FeedbackIds.writeMarker(noteId))
                .build();
    }

    FeedbackSendService pullRequest(List<LineNoteWriter> writers, FeedbackCopyState summary, FeedbackCopyState line) {
        when(item.kind()).thenReturn(new ItemKind("scm.pull_request"));
        when(notes.kind()).thenReturn(IntegrationKind.GITHUB);
        when(transactions.claim(eq(1L), eq(id), eq(owner)))
                .thenReturn(new FeedbackSendTransactions.Attempt(
                        feedback, FeedbackSendAction.POST, List.of(copy(summary), note(line)), 1));
        return new FeedbackSendService(
                transactions,
                targets,
                List.of(writer),
                writers,
                mock(PostedCopyCorrector.class),
                new FeedbackReadbackMeters(meters),
                mock(FeedbackProviderReadback.class),
                mock(FeedbackOwnCopyEditor.class),
                gate);
    }

    @Test
    void shouldSendTheSummaryAndNotesOfAPullRequestAsOnePackage() {
        var sender = pullRequest(List.of(notes), FeedbackCopyState.PLANNED, FeedbackCopyState.PLANNED);
        var posted = List.of(
                new CopyReceipt(copyId, CopyDisposition.POSTED, "PRR_1", null, true, null),
                new CopyReceipt(noteId, CopyDisposition.POSTED, "PRRC_1", null, true, null));
        when(notes.postPackage(eq(target), any(), any())).thenReturn(CopyWriteResult.of(posted));
        sender.send(owner);
        ArgumentCaptor<OnWorkPackage> sent = ArgumentCaptor.captor();
        verify(notes, times(1)).postPackage(eq(target), sent.capture(), any());
        assertThat(sent.getValue().copyIds()).containsExactly(copyId, noteId);
        assertThat(sent.getValue().summary()).isNotNull();
        verify(writer, never()).postSummary(any(), any());
        verify(transactions).receipt(eq(1L), eq(id), eq(owner), eq(posted));
    }

    @Test
    void shouldOnlyReadBackAPackageWhoseSubmissionMayHaveHappened() {
        var sender = pullRequest(List.of(notes), FeedbackCopyState.POSTED, FeedbackCopyState.WRITE_STARTED);
        when(notes.findPosted(eq(target), any())).thenReturn(List.of());
        sender.send(owner);
        verify(notes, never()).postPackage(any(), any(), any());
        verify(transactions).absent(eq(1L), eq(id), eq(owner), eq(noteId));
        verify(transactions, never()).absent(eq(1L), eq(id), eq(owner), eq(copyId));
        assertThat(readbacks("absent")).isEqualTo(1);
        assertThat(readbacks("found") + readbacks("unknown")).isZero();
    }

    @Test
    void shouldCountEachCopyOfAReadBackPackageByWhatTheProviderShowed() {
        var sender = pullRequest(List.of(notes), FeedbackCopyState.WRITE_STARTED, FeedbackCopyState.WRITE_STARTED);
        when(notes.findPosted(eq(target), any()))
                .thenReturn(List.of(
                        new CopyReceipt(copyId, CopyDisposition.PRESERVED_EXISTING, "PRR_1", null, true, null),
                        CopyReceipt.attempted(noteId)));
        sender.send(owner);
        verify(notes, never()).postPackage(any(), any(), any());
        assertThat(readbacks("found")).isEqualTo(1);
        assertThat(readbacks("unknown")).isEqualTo(1);
        assertThat(readbacks("absent")).isZero();
    }

    @Test
    void shouldKeepTheOutcomeUnknownWhenReadbackIsInconclusive() {
        var sender = pullRequest(List.of(notes), FeedbackCopyState.WRITE_STARTED, FeedbackCopyState.WRITE_STARTED);
        when(notes.findPosted(eq(target), any())).thenReturn(null);
        sender.send(owner);
        verify(notes, never()).postPackage(any(), any(), any());
        verify(transactions, never()).receipt(anyLong(), any(), eq(owner), anyList());
        verify(transactions, never()).absent(anyLong(), any(), eq(owner), any());
        assertThat(readbacks("unknown")).isEqualTo(2);
    }

    @Test
    void shouldRegisterEveryProviderAndOutcomeAtZeroWhenTheServerStarts() {
        assertThat(meters.find(PracticeMetrics.FEEDBACK_READBACK).counters())
                .extracting(counter -> counter.getId().getTag("provider") + "/"
                        + counter.getId().getTag("outcome"))
                .containsExactlyInAnyOrder(
                        "github/found",
                        "github/absent",
                        "github/unknown",
                        "gitlab/found",
                        "gitlab/absent",
                        "gitlab/unknown");
    }

    private double readbacks(String outcome) {
        return meters.counter(PracticeMetrics.FEEDBACK_READBACK, "provider", "github", "outcome", outcome)
                .count();
    }

    @Test
    void shouldWithholdThePackageWhenNoWriterServesTheProvider() {
        var sender = pullRequest(List.of(), FeedbackCopyState.PLANNED, FeedbackCopyState.PLANNED);
        sender.send(owner);
        verify(transactions)
                .withholdCopies(
                        eq(1L),
                        eq(id),
                        eq(owner),
                        eq(List.of(copyId, noteId)),
                        eq(FeedbackStopReason.RECIPIENT_UNAVAILABLE));
        verify(writer, never()).postSummary(any(), any());
    }
}
