package de.tum.cit.aet.hephaestus.practices.feedback.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.action.LaneAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackOutcomes;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPublicationContext;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendQueue;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckFacts;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionReason;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class FeedbackDecisionServiceTest {

    final FeedbackRepository feedback = mock(FeedbackRepository.class);
    final FeedbackDecisionRepository decisions = mock(FeedbackDecisionRepository.class);
    final FeedbackBasisRepository basis = mock(FeedbackBasisRepository.class);
    final FeedbackCopyRepository copies = mock(FeedbackCopyRepository.class);
    final AgentActions actions = mock(AgentActions.class);
    final FeedbackGate gate = mock(FeedbackGate.class);
    final PersonDataWriteFence privacy = mock(PersonDataWriteFence.class);
    final FeedbackContentDigest digests = mock(FeedbackContentDigest.class);
    final FeedbackPublicationContext publication = mock(FeedbackPublicationContext.class);
    final Instant now = Instant.EPOCH;
    final UUID id = UUID.randomUUID();
    final FeedbackDecisionService service = new FeedbackDecisionService(
            feedback,
            new FeedbackOutcomes(new SimpleMeterRegistry(), feedback, mock(ReviewRepository.class), decisions),
            decisions,
            basis,
            copies,
            Clock.fixed(now, ZoneOffset.UTC),
            gate,
            digests,
            privacy,
            new FeedbackSendQueue(
                    actions,
                    new ObjectMapper(),
                    copies,
                    mock(FeedbackVisibilityService.class),
                    mock(ConfigAuditPort.class)),
            publication);
    Feedback row;

    @BeforeEach
    void setup() {
        when(privacy.holdForAccountWrite(anyLong())).thenReturn(true);
        when(digests.current(1, id)).thenReturn(digest());
        row = Feedback.builder()
                .id(id)
                .workspaceId(1)
                .reviewId(UUID.randomUUID())
                .developerId(2)
                .destination(FeedbackDestination.ON_WORK)
                .state(FeedbackState.AWAITING_APPROVAL)
                .body("Add a regression test.")
                .build();
        when(feedback.lockByIdAndWorkspaceId(id, 1)).thenReturn(Optional.of(row));
        when(decisions.save(any())).thenAnswer(a -> a.getArgument(0));
        when(gate.evaluate(row, FeedbackCheckStage.APPROVED, false))
                .thenReturn(new FeedbackCheck(
                        FeedbackCheckStage.APPROVED,
                        true,
                        null,
                        now,
                        new FeedbackCheckFacts(0, 0, true, true, true, true, false, true, true, false)));
    }

    @Test
    void shouldRefuseNewDecisionTextDuringActorErasure() {
        when(privacy.holdForAccountWrite(3)).thenReturn(false);
        assertThatThrownBy(() -> service.decide(
                        1,
                        id,
                        3,
                        new FeedbackDecisionInput(FeedbackDecisionAction.WITHDRAW, null, "Withdraw this.", null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(decisions, never()).save(any());
    }

    String digest() {
        return "a".repeat(64);
    }

    @Test
    void shouldAppendApprovalAndQueueTheSamePreparedPiece() {
        var outcome = service.decide(
                1, id, 3, new FeedbackDecisionInput(FeedbackDecisionAction.APPROVE, null, null, digest()));
        var decision = saved();
        assertThat(outcome.decisionId()).isEqualTo(decision.getId());
        assertThat(outcome.state()).isEqualTo(FeedbackState.READY);
        assertThat(decision.getAction()).isEqualTo(FeedbackDecisionAction.APPROVE);
        assertThat(decision.getFeedbackId()).isEqualTo(id);
        var capture = ArgumentCaptor.forClass(ActionRequest.class);
        verify(actions).enqueue(capture.capture());
        assertThat(capture.getValue().idempotencyKey()).isEqualTo("feedback:" + id + ":1:POST");
    }

    @Test
    void shouldCommitAChangedPublicationContextAsAWithholdingInsteadOfAnApproval() {
        when(publication.refusal(row)).thenReturn(Optional.of(FeedbackStopReason.PUBLIC_HISTORY_CHANGED));
        var outcome = service.decide(
                1, id, 3, new FeedbackDecisionInput(FeedbackDecisionAction.APPROVE, null, null, digest()));
        assertThat(outcome.decisionId()).isNull();
        assertThat(outcome.refusal()).isEqualTo(FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
        assertThat(outcome.state()).isEqualTo(FeedbackState.WITHHELD);
        verify(feedback).updateChecks(eq(1L), eq(id), any());
        verify(copies).withholdPlanned(1, id, FeedbackStopReason.PUBLIC_HISTORY_CHANGED.name());
        verify(feedback).updateDelivery(1, id, FeedbackState.WITHHELD, FeedbackStopReason.PUBLIC_HISTORY_CHANGED, null);
        verify(decisions, never()).save(any());
        verify(actions, never()).enqueue(any());
    }

    @Test
    void shouldKeepAwaitingApprovalWhileAnotherReviewsWriteIsUnresolved() {
        when(publication.refusal(row)).thenReturn(Optional.of(FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED));
        var outcome = service.decide(
                1, id, 3, new FeedbackDecisionInput(FeedbackDecisionAction.APPROVE, null, null, digest()));
        assertThat(outcome.refusal()).isEqualTo(FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED);
        assertThat(outcome.state()).isEqualTo(FeedbackState.AWAITING_APPROVAL);
        verify(feedback).updateChecks(eq(1L), eq(id), any());
        verify(copies, never()).withholdPlanned(anyLong(), any(), any());
        verify(feedback, never()).updateDelivery(anyLong(), any(), any(), any(), any());
        verify(decisions, never()).save(any());
    }

    private FeedbackDecision saved() {
        var captured = ArgumentCaptor.forClass(FeedbackDecision.class);
        verify(decisions).save(captured.capture());
        return captured.getValue();
    }

    @Test
    void shouldRefuseChangedContentAndAnyExistingCopyFence() {
        assertThatThrownBy(() -> service.decide(
                        1,
                        id,
                        3,
                        new FeedbackDecisionInput(FeedbackDecisionAction.APPROVE, null, null, "0".repeat(64))))
                .isInstanceOf(ResponseStatusException.class);
        when(copies.hasWriteFence(1, id)).thenReturn(true);
        assertThatThrownBy(() -> service.decide(
                        1,
                        id,
                        3,
                        new FeedbackDecisionInput(
                                FeedbackDecisionAction.REJECT, FeedbackDecisionReason.INCORRECT, null, digest())))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldQueueCorrectionAgainstExistingReceiptsAfterWithdrawal() {
        when(copies.lockByFeedbackId(1, id))
                .thenReturn(List.of(FeedbackCopy.builder()
                        .id(UUID.randomUUID())
                        .state(FeedbackCopyState.POSTED)
                        .build()));
        var outcome = service.decide(
                1,
                id,
                3,
                new FeedbackDecisionInput(
                        FeedbackDecisionAction.WITHDRAW, FeedbackDecisionReason.OTHER, "Remove this guidance.", null));
        assertThat(outcome.refusal()).isNull();
        assertThat(saved().getAction()).isEqualTo(FeedbackDecisionAction.WITHDRAW);
        var capture = ArgumentCaptor.forClass(ActionRequest.class);
        verify(actions).enqueue(capture.capture());
        assertThat(capture.getValue().kind()).isEqualTo(ActionKind.RETRACT_FEEDBACK);
    }

    @Test
    void shouldKeepAnUnsettledPostAheadOfCorrection() {
        when(actions.newestInLane(1L, "feedback:" + id))
                .thenReturn(Optional.of(new LaneAction(
                        UUID.randomUUID(),
                        ActionKind.DELIVER_FEEDBACK,
                        "feedback:" + id + ":1:POST",
                        ActionState.EXECUTING,
                        1,
                        true,
                        now,
                        null,
                        now,
                        now)));
        when(copies.lockByFeedbackId(1, id))
                .thenReturn(List.of(
                        FeedbackCopy.builder().state(FeedbackCopyState.POSTED).build()));
        service.decide(
                1, id, 3, new FeedbackDecisionInput(FeedbackDecisionAction.WITHDRAW, null, "Withdraw it.", null));
        verify(actions, never()).enqueue(any());
    }
}
