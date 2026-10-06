package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchCompletion;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchDestination;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class FeedbackDispatchStateMachineTest extends BaseUnitTest {
    @Mock
    private FeedbackDispatchRepository repository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private MeterRegistry registry;

    private FeedbackDispatchStateMachine machine() {
        return new FeedbackDispatchStateMachine(repository, transactionTemplate, registry, new ObjectMapper());
    }

    @Test
    void shouldKeepDifferentObservationDeliveriesSeparateAtIdenticalCoordinates() {
        var anchor = FeedbackAnchor.DiffAnchor.singleLine("Same.java", 10);
        var first = new DeliveredSignal("observation:first", anchor, Disposition.POSTED, "1", null);
        var second = new DeliveredSignal("observation:second", anchor, Disposition.FAILED, null, null);
        assertThat(machine().mergeSignals(List.of(first), List.of(second))).containsExactly(first, second);
    }

    @Test
    void shouldMergeRetriesOnlyWhenExactDeliveryIdentityMatches() {
        var anchor = FeedbackAnchor.DiffAnchor.singleLine("Same.java", 10);
        var posted = new DeliveredSignal("observation:first", anchor, Disposition.POSTED, "1", null);
        var failedRetry = new DeliveredSignal("observation:first", anchor, Disposition.FAILED, null, null);
        assertThat(machine().mergeSignals(List.of(posted), List.of(failedRetry)))
                .containsExactly(posted);
    }

    @Test
    void shouldPreserveUnkeyedPlacementsWithoutInferringEquivalenceFromCoordinates() {
        var anchor = FeedbackAnchor.DiffAnchor.singleLine("Same.java", 10);
        var first = new DeliveredSignal(null, anchor, Disposition.POSTED, "1", null);
        var second = new DeliveredSignal(null, anchor, Disposition.POSTED, "2", null);
        assertThat(machine().mergeSignals(List.of(first), List.of(second))).containsExactly(first, second);
    }

    @Test
    void shouldPreserveKnownPermalinkOnlyForTheSameRecoveredComment() {
        var anchor = FeedbackAnchor.DiffAnchor.singleLine("Same.java", 10);
        var known = new DeliveredSignal(
                "key",
                anchor,
                Disposition.POSTED,
                "opaque:1",
                null,
                "https://gitlab.example.com/owner/repo/-/merge_requests/42#note_123",
                true,
                InlineFeedbackChannel.Placement.LOCATION_COMMENT);
        var same = new DeliveredSignal("key", anchor, Disposition.PRESERVED_EXISTING, "opaque:1", "thread");
        var replaced = new DeliveredSignal("key", anchor, Disposition.POSTED, "opaque:2", "thread");
        var kept = machine().mergeSignals(List.of(known), List.of(same)).getFirst();
        var other = machine().mergeSignals(List.of(known), List.of(replaced)).getFirst();
        assertThat(kept.externalUrl()).isEqualTo(known.externalUrl());
        assertThat(kept.placement()).isEqualTo(InlineFeedbackChannel.Placement.LOCATION_COMMENT);
        assertThat(other.externalUrl()).isNull();
        assertThat(other.placement()).isNull();
    }

    @Test
    void shouldNeverRelabelAWriteThatMayHaveStartedAsUnsent() {
        var anchor = FeedbackAnchor.DiffAnchor.singleLine("Same.java", 10);
        var unsent = DeliveredSignal.notSent("key", anchor);
        var attempted = DeliveredSignal.attempted("key", anchor);
        var historical = new DeliveredSignal("key", anchor, Disposition.FAILED, null, null);
        var postedWithoutId = new DeliveredSignal("key", anchor, Disposition.POSTED, null, null);

        assertThat(machine().mergeSignals(List.of(attempted), List.of(unsent))).containsExactly(attempted);
        assertThat(machine().mergeSignals(List.of(historical), List.of(unsent))).containsExactly(historical);
        assertThat(machine().mergeSignals(List.of(unsent), List.of(attempted))).containsExactly(attempted);
        assertThat(postedWithoutId.acknowledged()).isFalse();
        assertThat(postedWithoutId.unconfirmed()).isTrue();
    }

    @Test
    void shouldKeepTheStoredArrayShapeAndReadAMissingWriteFactAsUnknown() {
        var mapper = new ObjectMapper();
        var dispatch = mock(FeedbackDispatch.class);
        when(dispatch.getDeliveredPlacements()).thenReturn(mapper.readTree("""
            [{"recurrenceKey":"legacy","path":"Same.java","startLine":10,"disposition":"FAILED"},
             {"deliveryKey":"unsent","path":"Same.java","startLine":11,"disposition":"FAILED",
              "writeMayHaveStarted":false}]
            """));

        var signals = machine().deliveredSignals(dispatch);

        assertThat(signals.getFirst().deliveryKey()).isEqualTo("legacy");
        assertThat(signals.getFirst().writeMayHaveStarted()).isNull();
        assertThat(signals.getFirst().unconfirmed()).isTrue();
        assertThat(signals.getLast().writeMayHaveStarted()).isFalse();
        assertThat(signals.getLast().unconfirmed()).isFalse();
    }

    @Test
    void shouldReadStoredPermalinksAndKeepLegacyPlacementsWithoutInventingLinks() {
        var mapper = new ObjectMapper();
        var dispatch = mock(FeedbackDispatch.class);
        when(dispatch.getDeliveredPlacements()).thenReturn(mapper.readTree("""
            [{"deliveryKey":"key","path":"Same.java","startLine":10,"disposition":"POSTED",
              "externalRef":"opaque:1","externalUrl":"https://gitlab.example.com/a/b/-/merge_requests/1#note_123"},
             {"deliveryKey":"legacy","path":"Same.java","startLine":11,"disposition":"POSTED",
              "externalRef":"opaque:2"}]
            """));
        var signals = machine().deliveredSignals(dispatch);
        assertThat(signals.getFirst().externalUrl())
                .isEqualTo("https://gitlab.example.com/a/b/-/merge_requests/1#note_123");
        assertThat(signals.getLast().externalUrl()).isNull();
    }

    @Test
    void shouldReturnTheLineNotesAnUnsettledAttemptPersistedWhenItRetriesOrRechecks() {
        var posted = new DeliveredSignal(
                "approved:p:0", FeedbackAnchor.DiffAnchor.singleLine("Review.java", 12), Disposition.POSTED, "1", null);
        var failed = new DeliveredSignal(
                "approved:p:1",
                FeedbackAnchor.DiffAnchor.singleLine("Review.java", 20),
                Disposition.FAILED,
                null,
                null);
        var dispatch = mock(FeedbackDispatch.class);
        when(dispatch.getId()).thenReturn(UUID.randomUUID());
        when(dispatch.getWorkspaceId()).thenReturn(7L);
        when(dispatch.getAttemptCount()).thenReturn(1);
        when(dispatch.getDestination()).thenReturn(FeedbackDispatchDestination.APPROVED_REVIEW_PACKAGE);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        // The third completion finds the lease gone.
        when(repository.finish(any())).thenReturn(1, 1, 0);
        var machine = new FeedbackDispatchStateMachine(
                repository, transactionTemplate, new SimpleMeterRegistry(), new ObjectMapper());

        var retried = machine.retry(dispatch, "owner", "incomplete", null, null, true, List.of(posted, failed));
        var rechecked = machine.recheckAt(
                dispatch,
                "owner",
                "unconfirmed",
                null,
                null,
                List.of(posted, failed),
                Instant.now().plusSeconds(3600));
        var lost = machine.retry(dispatch, "owner", "incomplete", null, null, true, List.of(posted, failed));

        for (var result : List.of(retried, rechecked)) {
            assertThat(result.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.UNCERTAIN);
            assertThat(result.externalRef()).isNull();
            assertThat(result.deliveredSignals()).containsExactly(posted, failed);
        }
        assertThat(lost.status()).isEqualTo(PracticeFeedbackDispatchService.Result.Status.IN_PROGRESS);
        assertThat(lost.landed()).isFalse();
        var completions = ArgumentCaptor.forClass(FeedbackDispatchCompletion.class);
        verify(repository, times(3)).finish(completions.capture());
        for (var completion : completions.getAllValues()) {
            assertThat(completion.state()).isEqualTo(FeedbackDispatchState.UNCERTAIN.name());
            assertThat(completion.externalRef()).isNull();
            var stored = mock(FeedbackDispatch.class);
            when(stored.getDeliveredPlacements())
                    .thenReturn(new ObjectMapper().readTree(completion.deliveredPlacements()));
            assertThat(machine.deliveredSignals(stored)).containsExactly(posted, failed);
        }
    }
}
