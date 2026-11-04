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
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSend;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendQueue;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackStoreService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckFacts;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FeedbackSendTransactionsTest {

    final FeedbackStoreService store = mock(FeedbackStoreService.class);
    final FeedbackRepository parents = mock(FeedbackRepository.class);
    final FeedbackCopyRepository copies = mock(FeedbackCopyRepository.class);
    final FeedbackSendRepository sends = mock(FeedbackSendRepository.class);
    final FeedbackVisibilityService visibility = mock(FeedbackVisibilityService.class);
    final PersonDataWriteFence privacy = mock(PersonDataWriteFence.class);
    final Instant now = Instant.EPOCH;
    final UUID id = UUID.randomUUID(), copyId = UUID.randomUUID();
    final Feedback row = Feedback.builder().id(id).workspaceId(1).build();
    final FeedbackSendTransactions transactions = new FeedbackSendTransactions(
            store,
            new FeedbackOutcomes(
                    new SimpleMeterRegistry(),
                    parents,
                    mock(ReviewRepository.class),
                    mock(FeedbackDecisionRepository.class)),
            copies,
            sends,
            visibility,
            Clock.fixed(now, ZoneOffset.UTC),
            privacy,
            mock(FeedbackSendQueue.class),
            mock(ItemIndexService.class),
            mock(FeedbackPublicationContext.class));

    @BeforeEach
    void setup() {
        when(store.lock(1, id)).thenReturn(row);
        when(sends.lockByFeedbackId(1, id))
                .thenReturn(Optional.of(FeedbackSend.builder()
                        .state(FeedbackSendState.SENDING)
                        .action(FeedbackSendAction.POST)
                        .leaseOwner("owner")
                        .leaseExpiresAt(now.plusSeconds(120))
                        .build()));
    }

    @Test
    void shouldRefuseAnExpiredOwnerBeforeOpeningAnyFence() {
        assertThat(transactions.fence(1, id, "expired", List.of(copyId), List.of()))
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
        assertThat(transactions.fence(1, id, "owner", List.of(copyId), List.of()))
                .isTrue();
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
                1, id, "owner", List.of(new CopyReceipt(copyId, CopyDisposition.FAILED, null, null, null, null)));
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
                        "owner",
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
                        "owner",
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
                        "owner",
                        List.of(new CopyReceipt(
                                copyId, CopyDisposition.FELL_BACK, "ref", null, true, CopyForm.FILE_NOTE))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(copies, never()).confirmPosted(anyLong(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void shouldBlockNewPhysicalWritesWhileErasureHoldsTheAccount() {
        var writes = new AtomicInteger();
        assertThatThrownBy(() -> transactions.recipientWrite(2, writes::incrementAndGet))
                .isInstanceOf(FeedbackSendTransactions.RecipientWriteRefused.class);
        assertThat(writes).hasValue(0);
    }
}
