package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRateLimit;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;

/**
 * Unit tests for {@link ScopedRateLimitTracker}.
 */
class ScopedRateLimitTrackerTest extends BaseUnitTest {

    private ScopedRateLimitTracker tracker;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tracker = new ScopedRateLimitTracker(meterRegistry);
    }

    @Nested
    class InitialState {

        @Test
        void shouldReturnDefaultLimitForUnknownScope() {
            assertThat(tracker.getRemaining(999L)).isEqualTo(5000);
        }

        @Test
        void shouldReturnDefaultLimitForNullScope() {
            assertThat(tracker.getRemaining(null)).isEqualTo(5000);
        }

        @Test
        void shouldReturnDefaultFromGetLimitForUnknownScope() {
            assertThat(tracker.getLimit(999L)).isEqualTo(5000);
        }

        @Test
        void shouldReturnDefaultFromGetLimitForNullScope() {
            assertThat(tracker.getLimit(null)).isEqualTo(5000);
        }

        @Test
        void shouldReturnNullResetTimeForUnknownScope() {
            assertThat(tracker.getResetAt(999L)).isNull();
        }

        @Test
        void shouldReturnNullResetTimeForNullScope() {
            assertThat(tracker.getResetAt(null)).isNull();
        }

        @Test
        void shouldHaveZeroTrackedScopes() {
            assertThat(tracker.getTrackedScopeCount()).isZero();
        }
    }

    @Nested
    class UpdateFromResponse {

        @Test
        void shouldUpdateStateFromValidResponse() {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            ClientGraphQlResponse response = mockResponseWithRateLimit(4500, 5000, 2, resetTime);

            GHRateLimit result = tracker.updateFromResponse(scopeId, response);

            assertNotNull(result);
            assertThat(tracker.getRemaining(scopeId)).isEqualTo(4500);
            assertThat(tracker.getLimit(scopeId)).isEqualTo(5000);
            assertThat(tracker.getResetAt(scopeId)).isNotNull();
            assertThat(tracker.getTrackedScopeCount()).isEqualTo(1);
        }

        @Test
        void shouldReturnNullForNullScopeId() {
            // Use a plain mock — updateFromResponse returns null before touching the response
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);

            GHRateLimit result = tracker.updateFromResponse(null, response);

            assertThat(result).isNull();
        }

        @Test
        void shouldReturnNullForNullResponse() {
            GHRateLimit result = tracker.updateFromResponse(1L, null);

            assertThat(result).isNull();
        }

        @Test
        void shouldReturnNullForInvalidResponse() {
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.isValid()).thenReturn(false);

            GHRateLimit result = tracker.updateFromResponse(1L, response);

            assertThat(result).isNull();
        }

        @Test
        void shouldTrackMultipleScopesIndependently() {
            Long scope1 = 1L;
            Long scope2 = 2L;

            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scope1, mockResponseWithRateLimit(4000, 5000, 3, resetTime));
            tracker.updateFromResponse(scope2, mockResponseWithRateLimit(3000, 5000, 5, resetTime));

            assertThat(tracker.getRemaining(scope1)).isEqualTo(4000);
            assertThat(tracker.getRemaining(scope2)).isEqualTo(3000);
            assertThat(tracker.getTrackedScopeCount()).isEqualTo(2);
        }

        @Test
        void shouldUpdateExistingScopeState() {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);

            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(4500, 5000, 2, resetTime));
            assertThat(tracker.getRemaining(scopeId)).isEqualTo(4500);

            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(4000, 5000, 3, resetTime));
            assertThat(tracker.getRemaining(scopeId)).isEqualTo(4000);
            assertThat(tracker.getTrackedScopeCount()).isEqualTo(1);
        }
    }

    @Nested
    class IsCritical {

        @Test
        void shouldReturnTrueWhenBelowCriticalThreshold() {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(50, 5000, 10, resetTime));

            assertThat(tracker.isCritical(scopeId)).isTrue();
        }

        @Test
        void shouldReturnFalseWhenAtCriticalThreshold() {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(100, 5000, 10, resetTime));

            assertThat(tracker.isCritical(scopeId)).isFalse();
        }

        @Test
        void shouldReturnFalseWhenAboveCriticalThreshold() {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(4000, 5000, 5, resetTime));

            assertThat(tracker.isCritical(scopeId)).isFalse();
        }

        @Test
        void shouldReturnFalseForUnknownScope() {
            assertThat(tracker.isCritical(999L)).isFalse();
        }
    }

    @Nested
    class WaitIfNeeded {

        @Test
        void shouldNotWaitWhenAboveLowThreshold() throws InterruptedException {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(4000, 5000, 5, resetTime));

            boolean waited = tracker.waitIfNeeded(scopeId);

            assertThat(waited).isFalse();
        }

        @Test
        void shouldNotWaitWhenBetweenCriticalAndLow() throws InterruptedException {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(200, 5000, 10, resetTime));

            boolean waited = tracker.waitIfNeeded(scopeId);

            assertThat(waited).isFalse();
        }

        @Test
        void shouldNotWaitWhenResetTimeHasPassed() throws InterruptedException {
            Long scopeId = 1L;
            OffsetDateTime pastResetTime = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(50, 5000, 10, pastResetTime));

            boolean waited = tracker.waitIfNeeded(scopeId);

            assertThat(waited).isFalse();
        }
    }

    @Nested
    class Snapshot {

        @Test
        void shouldReturnNullForNeverObservedScope() {
            assertThat(tracker.snapshot(999L)).isNull();
        }

        @Test
        void shouldReturnNullForNullScope() {
            assertThat(tracker.snapshot(null)).isNull();
        }

        @Test
        void shouldReturnSnapshotAfterObservedResponse() {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(4200, 5000, 3, resetTime));

            var snapshot = tracker.snapshot(scopeId);

            assertNotNull(snapshot);
            assertThat(snapshot.limit()).isEqualTo(5000);
            assertThat(snapshot.remaining()).isEqualTo(4200);
            assertThat(snapshot.resetAt()).isEqualTo(resetTime.toInstant());
            assertThat(snapshot.observedAt()).isNotNull();
        }

        /**
         * GitHub reporting no {@code resetAt} must leave the snapshot's resetAt null, not serve the
         * constructor's fabricated {@code now() + 1h} as a measured value.
         */
        @Test
        void shouldNotFabricateResetAtWhenPayloadOmitsIt() {
            Long scopeId = 1L;

            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(4200, 5000, 3, null));

            var snapshot = tracker.snapshot(scopeId);
            assertNotNull(snapshot);
            assertThat(snapshot.resetAt()).isNull();
            assertThat(snapshot.remaining()).isEqualTo(4200);
            assertThat(snapshot.limit()).isEqualTo(5000);
        }

        /**
         * The "optimistic reset" is a throttling heuristic: it must move the decision APIs while leaving
         * the displayed observation untouched, never written into state and rendered as measured.
         */
        @Test
        void shouldNotLetOptimisticResetLeakIntoSnapshot() {
            Long scopeId = 1L;
            OffsetDateTime pastReset = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(7, 5000, 10, pastReset));

            // Decision API assumes a full budget because the window has rolled over...
            assertThat(tracker.getRemaining(scopeId)).isEqualTo(5000);

            // ...but nothing derived from that assumption is reported.
            var snapshot = tracker.snapshot(scopeId);
            assertNotNull(snapshot);
            assertThat(snapshot.remaining()).isNull();
            assertThat(snapshot.resetAt()).isNull();
            assertThat(snapshot.limit()).isEqualTo(5000); // a ceiling is window-invariant
        }

        @Test
        void shouldKeepObservedRemainingWhileWindowIsOpen() {
            Long scopeId = 1L;
            OffsetDateTime futureReset = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(7, 5000, 10, futureReset));

            var snapshot = tracker.snapshot(scopeId);
            assertNotNull(snapshot);
            assertThat(snapshot.remaining()).isEqualTo(7);
            assertThat(snapshot.resetAt()).isEqualTo(futureReset.toInstant());
        }

        @Test
        void shouldNeverReportThrottledUntilFromGraphQlObservations() {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(4200, 5000, 3, resetTime));

            assertNotNull(tracker.snapshot(scopeId));
            assertThat(required(tracker.snapshot(scopeId)).throttledUntil()).isNull();
        }
    }

    /**
     * The REST {@code GET /rate_limit} seed. Its whole point is that a scope's ceiling is a measured fact
     * before the first GraphQL call — and that the ceiling can be something other than the assumed 5000,
     * which is exactly the case for App installations with more than 20 repositories (up to 12,500).
     */
    @Nested
    class RestSeed {

        @Test
        void shouldSurfaceRestObservationAsARealSnapshot() {
            Long scopeId = 1L;
            Instant resetAt = Instant.now().plusSeconds(3600);
            Instant observedAt = Instant.now();

            tracker.updateFromRestRateLimit(scopeId, 12_500, 12_500, resetAt, observedAt);

            var snapshot = tracker.snapshot(scopeId);
            assertNotNull(snapshot);
            assertThat(snapshot.limit()).isEqualTo(12_500);
            assertThat(snapshot.remaining()).isEqualTo(12_500);
            assertThat(snapshot.resetAt()).isEqualTo(resetAt);
            assertThat(snapshot.observedAt()).isEqualTo(observedAt);
        }

        @Test
        void shouldMakeTheRealCeilingDriveThrottleDecisionsInsteadOfTheAssumedOne() {
            Long scopeId = 1L;

            assertThat(tracker.getLimit(scopeId)).isEqualTo(5000); // assumption, pre-seed
            tracker.updateFromRestRateLimit(
                    scopeId, 12_500, 12_400, Instant.now().plusSeconds(3600), Instant.now());

            assertThat(tracker.getLimit(scopeId)).isEqualTo(12_500);
        }

        @Test
        void shouldNotOverwriteAFresherObservationWithAStalerSeed() {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(4200, 5000, 3, resetTime));

            // A probe that was in flight before the GraphQL response landed.
            tracker.updateFromRestRateLimit(
                    scopeId, 5000, 4999, resetTime.toInstant(), Instant.now().minusSeconds(30));

            assertNotNull(tracker.snapshot(scopeId));
            assertThat(required(tracker.snapshot(scopeId)).remaining()).isEqualTo(4200);
        }

        @Test
        void shouldReportNoObservationUntilSomethingIsSeeded() {
            assertThat(tracker.hasObservation(7L)).isFalse();

            tracker.updateFromRestRateLimit(7L, 5000, 5000, Instant.now().plusSeconds(3600), Instant.now());

            assertThat(tracker.hasObservation(7L)).isTrue();
        }
    }

    @Nested
    class Metrics {

        @Test
        void shouldRegisterMetricsGaugeOnFirstUpdate() {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(4500, 5000, 2, resetTime));

            assertThat(meterRegistry
                            .find("github.graphql.ratelimit.points.remaining")
                            .tag("scope_id", "1")
                            .gauge())
                    .isNotNull();
            assertThat(meterRegistry
                            .find("github.graphql.ratelimit.points.limit")
                            .tag("scope_id", "1")
                            .gauge())
                    .isNotNull();
            assertThat(meterRegistry
                            .find("github.graphql.ratelimit.points.used")
                            .tag("scope_id", "1")
                            .gauge())
                    .isNotNull();
            assertThat(meterRegistry
                            .find("github.graphql.ratelimit.last_query_cost")
                            .tag("scope_id", "1")
                            .gauge())
                    .isNotNull();
            assertThat(meterRegistry
                            .find("github.graphql.ratelimit.seconds_until_reset")
                            .tag("scope_id", "1")
                            .gauge())
                    .isNotNull();
        }

        /**
         * The optimistic reset must not <em>write</em>. The gauge reads the same observed field the
         * snapshot does, so if a throttle decision ever mutates state back up to the limit, this gauge
         * reports 5000 for a budget nobody measured.
         */
        @Test
        void throttleDecisionMustNotWriteBackIntoObservedState() {
            Long scopeId = 1L;
            OffsetDateTime pastReset = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(7, 5000, 10, pastReset));

            // Exercise every decision API that could mutate state back up.
            tracker.getRemaining(scopeId);
            tracker.isCritical(scopeId);
            assertThatNoException().isThrownBy(() -> tracker.waitIfNeeded(scopeId));

            var remainingGauge = meterRegistry
                    .find("github.graphql.ratelimit.points.remaining")
                    .tag("scope_id", "1")
                    .gauge();
            assertNotNull(remainingGauge);
            assertThat(remainingGauge.value()).isEqualTo(7.0);
        }

        @Test
        void shouldReflectUpdatedValuesInGauges() {
            Long scopeId = 1L;
            OffsetDateTime resetTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
            tracker.updateFromResponse(scopeId, mockResponseWithRateLimit(4500, 5000, 2, resetTime));

            var remainingGauge = meterRegistry
                    .find("github.graphql.ratelimit.points.remaining")
                    .tag("scope_id", "1")
                    .gauge();
            assertNotNull(remainingGauge);
            assertThat(remainingGauge.value()).isEqualTo(4500.0);
            var limitGauge = meterRegistry
                    .find("github.graphql.ratelimit.points.limit")
                    .tag("scope_id", "1")
                    .gauge();
            assertNotNull(limitGauge);
            assertThat(limitGauge.value()).isEqualTo(5000.0);
        }
    }

    // Helper Methods

    /**
     * No committed operation document selects {@code rateLimit.used} or {@code rateLimit.nodeCount} — every one
     * asks for {@code cost limit remaining resetAt}. GitHub therefore never sends them, and the generated DTO's
     * primitive fields decode to 0. Stubbing a non-zero {@code used} would assert against a reply that cannot
     * arrive.
     */
    private ClientGraphQlResponse mockResponseWithRateLimit(
            int remaining, int limit, int cost, @Nullable OffsetDateTime resetAt) {
        GHRateLimit rateLimit = new GHRateLimit();
        rateLimit.setCost(cost);
        rateLimit.setLimit(limit);
        rateLimit.setRemaining(remaining);
        if (resetAt != null) rateLimit.setResetAt(resetAt);

        ClientResponseField field = mock(ClientResponseField.class);
        when(field.toEntity(GHRateLimit.class)).thenReturn(rateLimit);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        when(response.isValid()).thenReturn(true);
        when(response.field("rateLimit")).thenReturn(field);

        return response;
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}
