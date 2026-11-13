package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyForm;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyReadback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackOutcomes;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPublicationContext;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendQueue;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackStoreService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckFacts;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
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
import tools.jackson.databind.node.JsonNodeFactory;

@Tag("unit")
class FeedbackSendTransactionsTest {

    final FeedbackStoreService store = mock(FeedbackStoreService.class);
    final FeedbackRepository parents = mock(FeedbackRepository.class);
    final FeedbackCopyRepository copies = mock(FeedbackCopyRepository.class);
    final AgentActions actions = mock(AgentActions.class);
    final FeedbackVisibilityService visibility = mock(FeedbackVisibilityService.class);
    final PersonDataWriteFence privacy = mock(PersonDataWriteFence.class);
    final Instant now = Instant.EPOCH;
    final UUID id = UUID.randomUUID(), copyId = UUID.randomUUID();
    final Feedback row = Feedback.builder().id(id).workspaceId(1).developerId(2).build();
    final ActionAttempt owner = attempt(1), expired = attempt(2);
    final FeedbackSendTransactions transactions = new FeedbackSendTransactions(
            store,
            new FeedbackOutcomes(
                    new SimpleMeterRegistry(),
                    parents,
                    mock(ReviewRepository.class),
                    mock(FeedbackDecisionRepository.class)),
            copies,
            actions,
            visibility,
            Clock.fixed(now, ZoneOffset.UTC),
            privacy,
            mock(FeedbackSendQueue.class),
            mock(ItemIndexService.class),
            mock(FeedbackPublicationContext.class),
            parents);

    @BeforeEach
    void setup() {
        when(store.lock(1, id)).thenReturn(row);
        when(parents.developerOf(id, 1)).thenReturn(Optional.of(2L));
        when(privacy.holdForAccountWrite(2)).thenReturn(true);
        when(actions.holds(owner)).thenReturn(true);
    }

    /** A claimed post of the feedback; only {@link #owner} still holds its lease. */
    ActionAttempt attempt(long epoch) {
        return new ActionAttempt(
                UUID.randomUUID(),
                1L,
                ActionKind.DELIVER_FEEDBACK,
                "feedback:" + id + ":1:POST",
                JsonNodeFactory.instance.objectNode(),
                1,
                epoch,
                false,
                null,
                () -> true,
                null);
    }

    @Test
    void shouldRefuseAnExpiredOwnerBeforeOpeningAnyFence() {
        assertThat(transactions.fence(1, id, expired, List.of(copyId), List.of()))
                .isFalse();
        verify(copies, never()).beginWrite(anyLong(), any(), any(), any());
    }

    @Test
    void shouldFenceEachCopyAndExposeUnknownVisibilityBeforeTheCall() {
        when(store.check(row, true))
                .thenReturn(new FeedbackCheck(
                        FeedbackCheckStage.EGRESS,
                        true,
                        null,
                        now,
                        new FeedbackCheckFacts(0, 0, true, true, true, true, false, true, true, false)));
        when(copies.beginWrite(1, id, copyId, now)).thenReturn(1);
        assertThat(transactions.fence(1, id, owner, List.of(copyId), List.of())).isTrue();
        verify(parents).updateDelivery(1, id, FeedbackState.UNCONFIRMED, FeedbackStopReason.DELIVERY_UNCONFIRMED, null);
    }

    @Test
    void shouldNotTurnAnUnknownFailureFlagIntoKnownAbsence() {
        when(copies.findByIdAndWorkspaceId(copyId, 1))
                .thenReturn(Optional.of(FeedbackCopy.builder()
                        .id(copyId)
                        .feedbackId(id)
                        .state(FeedbackCopyState.WRITE_STARTED)
                        .build()));
        transactions.receipt(
                1, id, owner, List.of(new CopyReceipt(copyId, CopyDisposition.FAILED, null, null, null, null)));
        verify(copies, never()).recordAbsent(anyLong(), any(), any(), anyString());
        verify(copies)
                .recordError(
                        1,
                        id,
                        copyId,
                        FeedbackStopReason.DELIVERY_UNCONFIRMED.name(),
                        FeedbackCopyReadback.UNKNOWN.name(),
                        "Provider visibility is unknown.");
    }

    @Test
    void shouldRejectAChangedProviderReferenceOnARecordedReceipt() {
        when(copies.findByIdAndWorkspaceId(copyId, 1))
                .thenReturn(Optional.of(FeedbackCopy.builder()
                        .id(copyId)
                        .feedbackId(id)
                        .state(FeedbackCopyState.POSTED)
                        .externalRef("first")
                        .build()));
        assertThatThrownBy(() -> transactions.receipt(
                        1,
                        id,
                        owner,
                        List.of(new CopyReceipt(copyId, CopyDisposition.POSTED, "other", null, true, null))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRefuseAReceiptWithoutACommittedWriteFence() {
        when(copies.findByIdAndWorkspaceId(copyId, 1))
                .thenReturn(Optional.of(FeedbackCopy.builder()
                        .id(copyId)
                        .feedbackId(id)
                        .state(FeedbackCopyState.PLANNED)
                        .build()));
        assertThatThrownBy(() -> transactions.receipt(
                        1,
                        id,
                        owner,
                        List.of(new CopyReceipt(copyId, CopyDisposition.POSTED, "first", null, true, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotChangeASummaryIntoAnAnchoredCopyFromAProviderReceipt() {
        when(copies.findByIdAndWorkspaceId(copyId, 1))
                .thenReturn(Optional.of(FeedbackCopy.builder()
                        .id(copyId)
                        .feedbackId(id)
                        .kind(FeedbackCopyKind.SUMMARY)
                        .state(FeedbackCopyState.WRITE_STARTED)
                        .writeStartedAt(now)
                        .build()));
        assertThatThrownBy(() -> transactions.receipt(
                        1,
                        id,
                        owner,
                        List.of(new CopyReceipt(
                                copyId, CopyDisposition.FELL_BACK, "ref", null, true, CopyForm.FILE_NOTE))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(copies, never()).confirmPosted(anyLong(), any(), any(), any(), any(), any(), anyString(), any());
    }

    @Test
    void shouldWithholdAsPersonSuppressedAndStartNoCopyWhenTheFenceCannotHoldTheRecipient() {
        when(privacy.holdForAccountWrite(2)).thenReturn(false);
        assertThat(transactions.fence(1, id, owner, List.of(copyId), List.of())).isFalse();
        verify(store).withhold(row, FeedbackStopReason.PERSON_SUPPRESSED);
        verify(copies, never()).beginWrite(anyLong(), any(), any(), any());
    }

    @Test
    void shouldWithholdACorrectionWhenItCannotHoldTheRecipient() {
        when(privacy.holdForAccountWrite(2)).thenReturn(false);
        assertThat(transactions.admitCorrection(1, id, owner)).isFalse();
        verify(store).withhold(row, FeedbackStopReason.PERSON_SUPPRESSED);
    }

    @Test
    void shouldAdmitACorrectionOnlyForTheCurrentLeaseHolder() {
        when(privacy.holdForAccountWrite(2)).thenReturn(true);
        assertThat(transactions.admitCorrection(1, id, owner)).isTrue();
        assertThat(transactions.admitCorrection(1, id, expired)).isFalse();
        verify(store, never()).withhold(any(), any());
    }
}
