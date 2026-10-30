package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FeedbackSendStateMachineTest {

    private static final UUID FEEDBACK_ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    private static final Instant AT = Instant.parse("2026-10-14T10:00:00Z");

    @Test
    void shouldPreserveUncertaintyWhenOneSiblingHasAppeared() {
        var projection = FeedbackSendStateMachine.project(
                feedback(FeedbackState.READY, null),
                List.of(copy(0, FeedbackCopyState.POSTED, AT), copy(1, FeedbackCopyState.WRITE_STARTED, null)));
        assertThat(projection.state()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(projection.stopReason()).isEqualTo(FeedbackStopReason.DELIVERY_UNCONFIRMED);
        assertThat(projection.deliveredAt()).isEqualTo(AT);
    }

    @Test
    void shouldReportFailureAndKeepTheFirstConfirmedAppearanceWhenAnotherCopyFails() {
        var projection = FeedbackSendStateMachine.project(
                feedback(FeedbackState.UNCONFIRMED, AT),
                List.of(
                        copy(0, FeedbackCopyState.POSTED, AT.plusSeconds(10)),
                        copy(1, FeedbackCopyState.FAILED, null)));
        assertThat(projection.state()).isEqualTo(FeedbackState.FAILED);
        assertThat(projection.deliveredAt()).isEqualTo(AT);
    }

    @Test
    void shouldNotReportDeliveryWhileAnotherCopyIsPlanned() {
        var projection = FeedbackSendStateMachine.project(
                feedback(FeedbackState.READY, null),
                List.of(copy(0, FeedbackCopyState.POSTED, AT), copy(1, FeedbackCopyState.PLANNED, null)));
        assertThat(projection.state()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(projection.deliveredAt()).isEqualTo(AT);
    }

    @Test
    void shouldKeepTheReceiptWhenAProviderAcknowledgesRetraction() {
        var projection = FeedbackSendStateMachine.project(
                feedback(FeedbackState.DELIVERED, AT), List.of(copy(0, FeedbackCopyState.RETRACTED, AT)));
        assertThat(projection.state()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(projection.deliveredAt()).isEqualTo(AT);
    }

    @Test
    void shouldReportFailureWhenNoCopyAppearedAndTheWriteFailed() {
        var projection = FeedbackSendStateMachine.project(
                feedback(FeedbackState.READY, null), List.of(copy(0, FeedbackCopyState.FAILED, null)));
        assertThat(projection.state()).isEqualTo(FeedbackState.FAILED);
        assertThat(projection.deliveredAt()).isNull();
    }

    @Test
    void shouldKeepTheRecordedReasonWhenAllCopiesAreWithheld() {
        var projection = FeedbackSendStateMachine.project(
                feedback(FeedbackState.READY, null), List.of(copy(0, FeedbackCopyState.WITHHELD, null)));
        assertThat(projection.state()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(projection.stopReason()).isEqualTo(FeedbackStopReason.RESULT_MARKED_INCORRECT);
    }

    @Test
    void shouldKeepApprovalRequiredWhenNoProviderWriteStarted() {
        var projection = FeedbackSendStateMachine.project(
                feedback(FeedbackState.AWAITING_APPROVAL, null), List.of(copy(0, FeedbackCopyState.PLANNED, null)));
        assertThat(projection.state()).isEqualTo(FeedbackState.AWAITING_APPROVAL);
    }

    private static Feedback feedback(FeedbackState state, @Nullable Instant deliveredAt) {
        return Feedback.builder()
                .id(FEEDBACK_ID)
                .workspaceId(7)
                .reviewId(FEEDBACK_ID)
                .itemId(11L)
                .developerId(9)
                .destination(FeedbackDestination.ON_WORK)
                .state(state)
                .body("Check the return value.")
                .createdAt(AT)
                .deliveredAt(deliveredAt)
                .build();
    }

    private static FeedbackCopy copy(int position, FeedbackCopyState state, @Nullable Instant postedAt) {
        UUID id = new UUID(0, position + 1);
        return FeedbackCopy.builder()
                .id(id)
                .workspaceId(7)
                .feedbackId(FEEDBACK_ID)
                .kind(FeedbackCopyKind.SUMMARY)
                .position(position)
                .state(state)
                .writeKey(id.toString())
                .writeStartedAt(state == FeedbackCopyState.WRITE_STARTED ? AT : null)
                .stopReason(state == FeedbackCopyState.WITHHELD ? FeedbackStopReason.RESULT_MARKED_INCORRECT : null)
                .createdAt(AT)
                .postedAt(postedAt)
                .build();
    }
}
