package de.tum.cit.aet.hephaestus.integration.core.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerSignalRecorderTest extends BaseUnitTest {

    private static final SignalKey KEY =
            new SignalKey(7L, 42L, SignalName.of("scm.pull_request.ready"), SignalRevision.ofHeadCommit("abc123"));

    private final ArtifactSignalRepository repository = mock(ArtifactSignalRepository.class);
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final LedgerSignalRecorder recorder =
            new LedgerSignalRecorder(repository, new PracticeReviewRefusalMetrics(meterRegistry), meterRegistry);

    @Test
    void shouldCountDurablyDeferredOccasionsSeparatelyFromDuplicates() {
        when(repository.insertDeferred(eq(KEY), any(), any(), any(), isNull())).thenReturn(1, 0);

        assertThat(recorder.defer(KEY, Instant.now())).isTrue();
        assertThat(recorder.defer(KEY, Instant.now())).isFalse();

        assertThat(meterRegistry
                        .get("practice.review.occasions")
                        .tags("signal", KEY.signalName().value(), "outcome", "deferred")
                        .counter()
                        .count())
                .isOne();
        assertThat(meterRegistry
                        .get("practice.review.occasions")
                        .tags("signal", KEY.signalName().value(), "outcome", "duplicate")
                        .counter()
                        .count())
                .isOne();
    }

    @Test
    void shouldKeepTheIssueActorSeparateFromTheReviewRequester() {
        recorder.defer(KEY, Instant.now(), 99L);

        verify(repository).insertDeferred(eq(KEY), any(), any(), any(), eq(99L));
        verify(repository, never()).insertOrClaimUndecided(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void shouldCountRecordedOccasionsSeparatelyFromDuplicates() {
        when(repository.insertOrClaimUndecided(eq(KEY), any(), any(), eq("EVENT"), any(), any()))
                .thenReturn(1, 0);

        assertThat(recorder.record(KEY, Instant.now(), DiscoveredVia.EVENT)).isTrue();
        assertThat(recorder.record(KEY, Instant.now(), DiscoveredVia.EVENT)).isFalse();

        assertThat(meterRegistry
                        .get("practice.review.occasions")
                        .tags("signal", KEY.signalName().value(), "outcome", "recorded")
                        .counter()
                        .count())
                .isOne();
        assertThat(meterRegistry
                        .get("practice.review.occasions")
                        .tags("signal", KEY.signalName().value(), "outcome", "duplicate")
                        .counter()
                        .count())
                .isOne();
    }

    @Test
    void shouldNotLetAReconciliationPassDisplaceADecisionAlreadyTaken() {
        // A backfill that raced a live delivery must only ever add a row. If sync could take over an
        // already-decided signal it would re-run reviews for history it merely re-read.
        recorder.record(KEY, Instant.now(), DiscoveredVia.SYNC);

        verify(repository).insertIfAbsent(eq(KEY), any(), any(), eq("SYNC"), any());
        verify(repository, never()).insertOrClaimUndecided(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void shouldLetALiveDeliveryClaimASignalNobodyHasDecidedYet() {
        // The other half of the same rule: a sync that saw a transition seconds before the provider
        // announced it must not silently disable the live review.
        recorder.record(KEY, Instant.now(), DiscoveredVia.EVENT);

        verify(repository).insertOrClaimUndecided(eq(KEY), any(), any(), eq("EVENT"), any(), any());
        verify(repository, never()).insertIfAbsent(any(), any(), any(), anyString(), any());
    }

    @Test
    void shouldHoldARefusalAnOperatorCanLiftAsPending() {
        when(repository.markRefused(eq(KEY), eq("PENDING"), eq("BUDGET_EXHAUSTED"), any()))
                .thenReturn(1);

        recorder.markRefused(KEY, SignalStateReason.BUDGET_EXHAUSTED);

        verify(repository).markRefused(eq(KEY), eq("PENDING"), eq("BUDGET_EXHAUSTED"), any());
        assertThat(meterRegistry
                        .get("practice.review.refused")
                        .tags("phase", "submission", "reason", "budget_exhausted")
                        .counter()
                        .count())
                .isOne();
    }

    @Test
    void shouldNotKeepReOfferingARefusalThatWouldBeMadeAgain() {
        // Cooldown is rate limiting, not correctness: re-offering after it expires would defeat the
        // limit the workspace asked for.
        recorder.markRefused(KEY, SignalStateReason.COOLDOWN_ACTIVE);

        verify(repository).markRefused(eq(KEY), eq("SUPPRESSED"), eq("COOLDOWN_ACTIVE"), any());
    }

    @Test
    void shouldRecordTheJobThatCarriesTheReview() {
        UUID jobId = UUID.randomUUID();

        recorder.markTriggered(KEY, jobId);

        verify(repository).markTriggered(eq(KEY), eq(jobId), any());
    }

    @Test
    void shouldWriteTheArtifactKindDerivedFromTheSignalName() {
        recorder.record(KEY, Instant.now(), DiscoveredVia.EVENT);

        verify(repository)
                .insertOrClaimUndecided(
                        org.mockito.ArgumentMatchers.argThat(
                                key -> key.artifactKind().equals(ArtifactKind.of("scm.pull_request"))),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    void shouldAttributeAHandRequestedSignalToWhoeverAskedForIt() {
        // The per-person request allowance counts these rows, so the requester has to land in the same
        // statement as the row. Written afterwards, there is a window in which the limit cannot see it.
        recorder.record(KEY, Instant.now(), DiscoveredVia.MANUAL, 99L);

        verify(repository).insertOrClaimUndecided(eq(KEY), any(), any(), eq("MANUAL"), any(), eq(99L));
    }

    @Test
    void shouldRefuseToAttributeASyncDiscoveryToAPerson() {
        // A sync notices what already happened; nobody asked it to. Letting a background pass name a
        // requester would spend that person's hourly allowance on work they never commissioned.
        assertThatThrownBy(() -> recorder.record(KEY, Instant.now(), DiscoveredVia.SYNC, 99L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
