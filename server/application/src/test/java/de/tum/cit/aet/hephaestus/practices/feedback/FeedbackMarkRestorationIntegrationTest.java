package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.LookupPresence;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkChanged;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FeedbackMarkRestorationIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Autowired
    FeedbackMarkListener feedbackMarks;

    @Autowired
    FeedbackContentDigest digests;

    private UUID mark(UUID resultId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                        + " result_mark(id,workspace_id,result_id,kind,reason,created_by_id,created_at)"
                        + " VALUES (?,?,?,'INCORRECT','Review the basis.',?,now())",
                id,
                workspace.getId(),
                resultId,
                developerId);
        feedbackMarks.marked(
                new ResultMarkChanged(id, workspace.getId(), resultId, ResultMarkKind.INCORRECT, false, Instant.now()));
        return id;
    }

    private void remove(UUID id) {
        UUID resultId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT result_id FROM result_mark WHERE id=? AND workspace_id=?", UUID.class, id, workspace.getId()));
        jdbc.update(
                "UPDATE result_mark SET removed_at=now(),removed_by_id=?,removal_reason='The basis"
                        + " is confirmed.' WHERE id=? AND workspace_id=?",
                developerId,
                id,
                workspace.getId());
        feedbackMarks.marked(
                new ResultMarkChanged(id, workspace.getId(), resultId, ResultMarkKind.INCORRECT, true, Instant.now()));
    }

    @Test
    void shouldKeepFeedbackWithheldUntilEveryBasisResultIsUnmarked() {
        var other = negativeResult();
        var draft = draft(false);
        store.prepareAll(
                workspace.getId(),
                reviewId,
                List.of(new FeedbackDraft(
                        draft.id(),
                        draft.workspaceId(),
                        draft.reviewId(),
                        draft.itemId(),
                        draft.developerId(),
                        draft.destination(),
                        draft.state(),
                        draft.stopReason(),
                        draft.body(),
                        List.of(draft.basis().getFirst(), new FeedbackBasisInput(other.getId(), null, 1)),
                        draft.copies(),
                        draft.checks(),
                        null,
                        null)));
        UUID first = mark(supportingResult.getId()), second = mark(other.getId());
        assertThat(current().getStopReason()).isEqualTo(FeedbackStopReason.RESULT_MARKED_INCORRECT);
        remove(first);
        assertThat(current().getState()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(children().getFirst().getWriteStartedAt()).isNull();
        remove(second);
        assertThat(current().getState()).isEqualTo(FeedbackState.READY);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.PLANNED);
        sender.send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(results.findById(supportingResult.getId()).orElseThrow().getEndedAt())
                .isNull();
    }

    @Test
    void shouldPreserveAnAdministratorRejectionWhenTheMarkIsRemoved() {
        when(gate.requiresApproval(any())).thenReturn(true);
        prepare(false);
        store.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(
                        FeedbackDecisionAction.REJECT,
                        null,
                        "Do not send this guidance.",
                        digests.current(workspace.getId(), feedbackId)));
        UUID id = mark(supportingResult.getId());
        remove(id);
        assertThat(current().getState()).isEqualTo(FeedbackState.REJECTED);
        sender.send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isZero();
    }

    @Test
    void shouldRestoreTheSameConfirmedSummaryAfterAMarkWithoutAnotherPost() {
        prepare(false);
        sender.send(workspace.getId(), feedbackId);
        var copy = children().getFirst();
        UUID id = mark(supportingResult.getId());
        assertThat(sends.findById(feedbackId).orElseThrow().getAction()).isEqualTo(FeedbackSendAction.RETRACT);
        newSender().send(workspace.getId(), feedbackId);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        remove(id);
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.updates).isEqualTo(2);
        assertThat(children().getFirst().getExternalRef()).isEqualTo(copy.getExternalRef());
        assertThat(current().getDeliveredAt()).isNotNull();
    }

    @Test
    void shouldWriteARetractedNoteBackIntoTheSameProviderNote() {
        provider.retraction = CopyUpdateKind.EDITED;
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        var original = children().get(1);
        UUID id = mark(supportingResult.getId());
        newSender().send(workspace.getId(), feedbackId);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        remove(id);
        newSender().send(workspace.getId(), feedbackId);
        var restored = children().get(1);
        assertThat(restored.getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(restored.getLastError()).isNull();
        assertThat(restored.getExternalRef()).isEqualTo(original.getExternalRef());
        assertThat(restored.getPostedAt()).isEqualTo(original.getPostedAt());
        assertThat(provider.noteCreates).isEqualTo(1);
    }

    @Test
    void shouldLeaveANoteRetractedWithItsReasonWhenTheProviderNoLongerHasIt() {
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        UUID id = mark(supportingResult.getId());
        newSender().send(workspace.getId(), feedbackId);
        remove(id);
        newSender().send(workspace.getId(), feedbackId);
        var gone = children().get(1);
        assertThat(gone.getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        assertThat(gone.getLastError()).isEqualTo("The provider copy no longer exists, so it stays retracted.");
        assertThat(provider.noteCreates).isEqualTo(1);
    }

    @Test
    void shouldWithdrawAndRestoreOnlyTheCopiesThatRestOnTheMarkedResult() {
        provider.retraction = CopyUpdateKind.EDITED;
        Result other = negativeResult();
        prepareSeparateSupport(other);
        sender.send(workspace.getId(), feedbackId);
        var posted = children();
        UUID marked = posted.get(1).getId();

        UUID id = mark(supportingResult.getId());
        newSender().send(workspace.getId(), feedbackId);

        assertThat(provider.retracted).containsExactly(marked);
        assertThat(children())
                .extracting(FeedbackCopy::getState)
                .containsExactly(FeedbackCopyState.POSTED, FeedbackCopyState.RETRACTED, FeedbackCopyState.POSTED);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);

        remove(id);
        newSender().send(workspace.getId(), feedbackId);

        assertThat(provider.restored).containsExactly(marked);
        assertThat(children()).extracting(FeedbackCopy::getState).containsOnly(FeedbackCopyState.POSTED);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.noteCreates).isEqualTo(2);
    }

    @Test
    void shouldKeepACopyWithdrawnUntilTheLastMarkOnItsSupportIsRemoved() {
        provider.retraction = CopyUpdateKind.EDITED;
        Result other = negativeResult();
        prepare(supportOf(List.of(other), List.of(supportingResult, other), List.of(other)), other);
        sender.send(workspace.getId(), feedbackId);
        UUID note = children().get(1).getId();
        UUID first = mark(supportingResult.getId());
        newSender().send(workspace.getId(), feedbackId);
        remove(first);
        UUID second = mark(other.getId());
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        assertThat(provider.restored).doesNotContain(note);
        remove(second);
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(children()).extracting(FeedbackCopy::getState).containsOnly(FeedbackCopyState.POSTED);
    }

    @Test
    void shouldHoldBackOnlyTheUnsentCopyThatRestsOnTheMarkedResult() {
        Result other = negativeResult();
        prepareSeparateSupport(other);

        UUID id = mark(supportingResult.getId());
        sender.send(workspace.getId(), feedbackId);

        var sent = children();
        assertThat(sent)
                .extracting(FeedbackCopy::getState)
                .containsExactly(FeedbackCopyState.POSTED, FeedbackCopyState.WITHHELD, FeedbackCopyState.POSTED);
        assertThat(sent.get(1).getStopReason()).isEqualTo(FeedbackStopReason.RESULT_MARKED_INCORRECT);
        assertThat(provider.noteCreates).isEqualTo(1);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);

        remove(id);
        newSender().send(workspace.getId(), feedbackId);

        assertThat(children()).extracting(FeedbackCopy::getState).containsOnly(FeedbackCopyState.POSTED);
        assertThat(provider.noteCreates).isEqualTo(2);
        assertThat(provider.summaryCreates).isEqualTo(1);
    }

    /** The summary and the second note rest on {@code other}. The first note rests on the supporting result. */
    private void prepareSeparateSupport(Result other) {
        prepare(supportOf(List.of(other), List.of(supportingResult), List.of(other)), other);
    }

    private static FeedbackCompositionTrace supportOf(
            List<Result> summary, List<Result> firstNote, List<Result> secondNote) {
        return new FeedbackCompositionTrace(
                List.of(
                        copySupport(FeedbackCopyKind.SUMMARY, 0, summary),
                        copySupport(FeedbackCopyKind.LINE_NOTE, 1, firstNote),
                        copySupport(FeedbackCopyKind.LINE_NOTE, 2, secondNote)),
                List.of(),
                null,
                null);
    }

    private static FeedbackCompositionTrace.CopySupport copySupport(
            FeedbackCopyKind kind, int position, List<Result> support) {
        return new FeedbackCompositionTrace.CopySupport(
                kind,
                position,
                support.stream()
                        .map(result -> new FeedbackCompositionTrace.Support(result.getId(), null))
                        .toList());
    }

    private void prepare(FeedbackCompositionTrace trace, Result other) {
        var base = draft(true);
        UUID second = FeedbackIds.copy(feedbackId, FeedbackCopyKind.LINE_NOTE, 2);
        var copies = new ArrayList<>(base.copies());
        copies.add(new FeedbackCopyInput(
                second,
                FeedbackCopyKind.LINE_NOTE,
                2,
                "Name the second failure.",
                "src/B.java",
                3,
                3,
                CitationSide.NEW,
                second.toString(),
                FeedbackIds.writeMarker(second)));
        store.prepareAll(
                workspace.getId(),
                reviewId,
                List.of(new FeedbackDraft(
                        base.id(),
                        base.workspaceId(),
                        base.reviewId(),
                        base.itemId(),
                        base.developerId(),
                        base.destination(),
                        base.state(),
                        base.stopReason(),
                        base.body(),
                        List.of(
                                new FeedbackBasisInput(supportingResult.getId(), null, 0),
                                new FeedbackBasisInput(other.getId(), null, 1)),
                        copies,
                        base.checks(),
                        trace,
                        null)));
    }

    @Test
    void shouldRestoreAnAuthorizedAbsentRetryAfterAnIncorrectMarkIsRemoved() {
        prepare(false);
        provider.summaryFailure = Failure.BEFORE_CREATE;
        sender.send(workspace.getId(), feedbackId);
        var firstFence = children().getFirst().getWriteStartedAt();
        provider.summaryFailure = Failure.NONE;
        provider.readback = LookupPresence.ABSENT;
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(children().getFirst().getReadback()).isEqualTo(FeedbackCopyReadback.ABSENT);
        store.retry(workspace.getId(), feedbackId, developerId);
        UUID id = mark(supportingResult.getId());
        assertThat(current().getState()).isEqualTo(FeedbackState.WITHHELD);
        remove(id);
        assertThat(current().getState()).isEqualTo(FeedbackState.READY);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.PLANNED);
        assertThat(children().getFirst().getWriteStartedAt()).isEqualTo(firstFence);
        newSender().send(workspace.getId(), feedbackId);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(children().getFirst().getWriteStartedAt()).isEqualTo(firstFence);
        assertThat(provider.physical).containsOnlyKeys(children().getFirst().getId());
    }
}
