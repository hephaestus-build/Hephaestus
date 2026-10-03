package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry.Route;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MentorProxyCredentialRegistryTest extends BaseUnitTest {

    private final MentorProxyCredentialRegistry registry = new MentorProxyCredentialRegistry();

    @Test
    void revokeIsIdempotent() {
        UUID sessionId = UUID.randomUUID();
        String token = registry.mint(
                sessionId, new Route("openai-completions", "https://api.openai.com", null, null, null, null));

        registry.revoke(sessionId);
        registry.revoke(sessionId);

        assertThat(registry.validate(token)).isEmpty();
    }

    @Test
    void revokingAnUnknownSessionLeavesEveryLiveSessionUntouched() {
        UUID liveSession = UUID.randomUUID();
        String liveToken = registry.mint(
                liveSession, new Route("openai-completions", "https://api.openai.com", null, null, null, null));

        registry.revoke(UUID.randomUUID());

        assertThat(registry.validate(liveToken)).isPresent();
        assertThat(registry.trackedSessions()).isEqualTo(1);
    }

    @Test
    void revokingOneSessionDoesNotAffectAnother() {
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        String tokenA = registry.mint(
                sessionA, new Route("openai-completions", "https://api.openai.com", null, null, null, null));
        String tokenB = registry.mint(
                sessionB, new Route("anthropic-messages", "https://api.anthropic.com", null, null, null, null));

        registry.revoke(sessionA);

        assertThat(registry.validate(tokenA)).isEmpty();
        assertThat(registry.validate(tokenB)).isPresent();
    }

    @Test
    void validateReportsRoutingForTheBoundConnection() {
        UUID sessionId = UUID.randomUUID();
        String token = registry.mint(
                sessionId,
                new Route("anthropic-messages", "https://api.anthropic.com", FundingSource.INSTANCE, 42L, null, null));

        var routing = registry.validate(token).orElseThrow();

        assertThat(routing.apiProtocol()).isEqualTo("anthropic-messages");
        assertThat(routing.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(routing.connectionScope()).isEqualTo(FundingSource.INSTANCE);
        assertThat(routing.connectionId()).isEqualTo(42L);
    }

    /**
     * The principal is what the sandbox gateway keys its rate-limit bucket on and what the proxy puts in
     * its MDC. A principal that named only the role would file every mentor session on a worker under one
     * bucket, and one workspace's conversation would throttle another's.
     */
    @Test
    void namesTheSessionInThePrincipalSoTwoSessionsNeverShareABucket() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String firstToken =
                registry.mint(first, new Route("openai-completions", "https://api.openai.com", null, null, null, null));
        String secondToken = registry.mint(
                second, new Route("anthropic-messages", "https://api.anthropic.com", null, null, null, null));

        assertThat(registry.validate(firstToken).orElseThrow().principalDescription())
                .isEqualTo("mentor-session:" + first)
                .isNotEqualTo(registry.validate(secondToken).orElseThrow().principalDescription());
    }

    /**
     * The turn binding: what makes a mentor turn's spend visible while the turn is still running. A
     * {@code validate} that returned no billing target would let a turn already at the cap make
     * unbounded calls before anything observed a cent of it.
     */
    @Nested
    class TurnBinding {

        private final MentorProxyCredentialRegistry registry = new MentorProxyCredentialRegistry();

        private static final LlmPriceSnapshot TEN_DOLLARS_PER_MILLION = new LlmPriceSnapshot(
                FundingSource.INSTANCE,
                PricingState.PRICED,
                1L,
                null,
                new BigDecimal("10"),
                new BigDecimal("10"),
                new BigDecimal("10"),
                new BigDecimal("10"));

        private String mint(UUID sessionId) {
            return registry.mint(
                    sessionId,
                    new Route("openai-completions", "https://api.openai.com", FundingSource.INSTANCE, 1L, 2L, 3L));
        }

        @Test
        void aSessionWithNoTurnRunningCarriesNoBillingTarget() {
            String token = mint(UUID.randomUUID());

            assertThat(registry.validate(token).orElseThrow().attempt()).isNull();
        }

        @Test
        @DisplayName("a bound turn's completed calls are reported as this call's in-flight spend")
        void aBoundTurnReportsWhatItHasAlreadySpent() {
            UUID sessionId = UUID.randomUUID();
            String token = mint(sessionId);
            UUID turnId = UUID.randomUUID();
            MentorTurnMeter meter = new MentorTurnMeter(turnId, TEN_DOLLARS_PER_MILLION);
            assertThat(registry.bindTurn(sessionId, meter)).isTrue();

            // Two completed calls of 100k input tokens each, at $10 per million = $1.00 each.
            registry.accumulate(turnId, new ProxyTokenUsage(100_000, 0, 0, 0, 0));
            registry.accumulate(turnId, new ProxyTokenUsage(100_000, 0, 0, 0, 0));

            var attempt = registry.validate(token).orElseThrow().attempt();
            assertThat(attempt).isNotNull();
            assertThat(attempt.sourceType()).isEqualTo(LlmUsageSourceType.MENTOR_TURN);
            assertThat(attempt.sourceId()).isEqualTo(turnId);
            assertThat(attempt.number()).isZero();
            assertThat(attempt.spentUsd()).isEqualByComparingTo("2.00");
            assertThat(registry.validate(token).orElseThrow().inFlightSpendUsd())
                    .isEqualByComparingTo("2.00");
        }

        @Test
        @DisplayName("a call that outlives its turn is dropped, not billed to the next turn")
        void aLateCallDoesNotLandOnTheFollowingTurn() {
            UUID sessionId = UUID.randomUUID();
            mint(sessionId);
            MentorTurnMeter turnA = new MentorTurnMeter(UUID.randomUUID(), TEN_DOLLARS_PER_MILLION);
            MentorTurnMeter turnB = new MentorTurnMeter(UUID.randomUUID(), TEN_DOLLARS_PER_MILLION);

            registry.bindTurn(sessionId, turnA);
            registry.unbindTurn(sessionId, turnA);
            registry.bindTurn(sessionId, turnB);

            boolean applied = registry.accumulate(turnA.turnId(), new ProxyTokenUsage(999, 0, 0, 0, 0));

            assertThat(applied).as("the fence rejects it").isFalse();
            assertThat(turnB.observed().isEmpty()).as("turn B is untouched").isTrue();
        }

        @Test
        void unbindingStopsFurtherUsageButKeepsWhatWasObserved() {
            UUID sessionId = UUID.randomUUID();
            String token = mint(sessionId);
            MentorTurnMeter meter = new MentorTurnMeter(UUID.randomUUID(), TEN_DOLLARS_PER_MILLION);
            registry.bindTurn(sessionId, meter);
            registry.accumulate(meter.turnId(), new ProxyTokenUsage(50_000, 10, 0, 0, 0));

            registry.unbindTurn(sessionId, meter);

            assertThat(registry.validate(token).orElseThrow().attempt())
                    .as("no longer billable")
                    .isNull();
            assertThat(meter.observed().inputTokens())
                    .as("still readable by the terminal write")
                    .isEqualTo(50_000);
            assertThat(registry.boundTurns()).isZero();
        }

        @Test
        void aStaleUnbindDoesNotDetachTheTurnRunningNow() {
            UUID sessionId = UUID.randomUUID();
            String token = mint(sessionId);
            MentorTurnMeter turnA = new MentorTurnMeter(UUID.randomUUID(), TEN_DOLLARS_PER_MILLION);
            MentorTurnMeter turnB = new MentorTurnMeter(UUID.randomUUID(), TEN_DOLLARS_PER_MILLION);
            registry.bindTurn(sessionId, turnA);
            registry.bindTurn(sessionId, turnB);

            registry.unbindTurn(sessionId, turnA);

            var attempt = registry.validate(token).orElseThrow().attempt();
            assertThat(attempt).isNotNull();
            assertThat(attempt.sourceId()).isEqualTo(turnB.turnId());
        }

        @Test
        void bindingANewTurnDetachesTheOneItReplaces() {
            UUID sessionId = UUID.randomUUID();
            mint(sessionId);
            MentorTurnMeter turnA = new MentorTurnMeter(UUID.randomUUID(), TEN_DOLLARS_PER_MILLION);
            registry.bindTurn(sessionId, turnA);

            registry.bindTurn(sessionId, new MentorTurnMeter(UUID.randomUUID(), TEN_DOLLARS_PER_MILLION));

            assertThat(registry.accumulate(turnA.turnId(), new ProxyTokenUsage(10, 0, 0, 0, 0)))
                    .isFalse();
            assertThat(registry.boundTurns()).isEqualTo(1);
        }

        @Test
        void bindingToASessionWithNoLiveCredentialFails() {
            UUID sessionId = UUID.randomUUID();
            mint(sessionId);
            registry.revoke(sessionId);

            boolean bound =
                    registry.bindTurn(sessionId, new MentorTurnMeter(UUID.randomUUID(), TEN_DOLLARS_PER_MILLION));

            assertThat(bound).isFalse();
            assertThat(registry.boundTurns()).isZero();
        }

        @Test
        void revokingASessionMidTurnReleasesTheBoundTurn() {
            UUID sessionId = UUID.randomUUID();
            mint(sessionId);
            MentorTurnMeter meter = new MentorTurnMeter(UUID.randomUUID(), TEN_DOLLARS_PER_MILLION);
            registry.bindTurn(sessionId, meter);

            registry.revoke(sessionId);

            assertThat(registry.boundTurns()).isZero();
            assertThat(registry.accumulate(meter.turnId(), new ProxyTokenUsage(10, 0, 0, 0, 0)))
                    .isFalse();
        }

        @Test
        void anUnpricedTurnReportsZeroSpendRatherThanFailing() {
            UUID sessionId = UUID.randomUUID();
            String token = mint(sessionId);
            MentorTurnMeter meter = new MentorTurnMeter(UUID.randomUUID(), null);
            registry.bindTurn(sessionId, meter);
            registry.accumulate(meter.turnId(), new ProxyTokenUsage(1_000_000, 0, 0, 0, 0));

            var attempt = registry.validate(token).orElseThrow().attempt();
            assertThat(attempt).isNotNull();
            assertThat(attempt.spentUsd()).isEqualByComparingTo("0");
            assertThat(meter.observed().inputTokens()).isEqualTo(1_000_000);
        }
    }

    /**
     * The TTL backstop exists for sandboxes that never reach their dispose callback — which is
     * precisely the case where nobody ever presents the token again. So expiry has to happen on its
     * own clock, and it has to take the session index with it, or a worker accumulates one dead pair
     * per failed sandbox build for as long as it runs.
     */
    @Nested
    class TtlBackstop {

        private final FakeTicker ticker = new FakeTicker();
        private final MentorProxyCredentialRegistry registry = new MentorProxyCredentialRegistry(ticker);

        @Test
        void aTokenThatIsNeverPresentedAgainStillExpiresAndDropsItsSessionEntry() {
            UUID sessionId = UUID.randomUUID();
            String token = registry.mint(
                    sessionId, new Route("openai-completions", "https://api.openai.com", null, null, null, null));
            assertThat(registry.trackedSessions()).isEqualTo(1);

            ticker.advance(Duration.ofHours(13));
            registry.runPendingEviction();

            assertThat(registry.validate(token)).isEmpty();
            assertThat(registry.trackedSessions()).isZero();
        }

        @Test
        void aTokenInsideItsTtlSurvives() {
            UUID sessionId = UUID.randomUUID();
            String token = registry.mint(
                    sessionId, new Route("openai-completions", "https://api.openai.com", null, null, null, null));

            ticker.advance(Duration.ofHours(11));
            registry.runPendingEviction();

            assertThat(registry.validate(token)).isPresent();
            assertThat(registry.trackedSessions()).isEqualTo(1);
        }

        @Test
        void reMintingForOneSessionRetiresTheTokenItReplaces() {
            UUID sessionId = UUID.randomUUID();
            String first = registry.mint(
                    sessionId, new Route("openai-completions", "https://api.openai.com", null, null, null, null));
            String second = registry.mint(
                    sessionId, new Route("openai-completions", "https://api.openai.com", null, null, null, null));

            assertThat(registry.validate(first)).isEmpty();
            assertThat(registry.validate(second)).isPresent();
            assertThat(registry.trackedSessions()).isEqualTo(1);

            registry.revoke(sessionId);
            assertThat(registry.validate(second)).isEmpty();
            assertThat(registry.trackedSessions()).isZero();
        }
    }

    private static final class FakeTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long read() {
            return nanos.get();
        }
    }
}
