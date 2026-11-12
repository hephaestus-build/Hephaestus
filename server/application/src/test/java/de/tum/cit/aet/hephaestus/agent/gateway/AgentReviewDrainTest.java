package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;

class AgentReviewDrainTest extends BaseUnitTest {
    private final AgentRuns runs = mock(AgentRuns.class);
    private final ToolsPods runtime = mock(ToolsPods.class, CALLS_REAL_METHODS);
    private final ApplicationAvailability availability = mock(ApplicationAvailability.class);

    private AgentReviewDrain drain(Duration timeout) {
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        return new AgentReviewDrain(
                runs,
                new AgentProperties(true, timeout, Duration.ofDays(14), Duration.ofDays(90)),
                availability,
                runtime);
    }

    @Test
    void shouldRefuseNewClaimsWithoutPassingIsolationProof() {
        AgentReviewDrain drain = drain(Duration.ZERO);
        drain.start();
        when(runtime.ready()).thenReturn(false);
        assertThat(drain.claim(() -> Optional.of("unproved"))).isEmpty();
        drain.stop();
    }

    @Test
    void shouldWaitForLeasedReviewsWhileRejectingNewClaims() throws Exception {
        AgentReviewDrain drain = drain(Duration.ofSeconds(10));
        drain.start();
        assertThat(drain.claim(() -> Optional.of("before shutdown"))).isPresent();
        var leased = new AtomicInteger(1);
        var counted = new CountDownLatch(1);
        when(runs.leasedReviews()).thenAnswer(call -> {
            counted.countDown();
            return leased.get();
        });
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(drain::stop);
        try {
            assertThat(counted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stopped).isNotDone();
            assertThat(drain.claim(() -> Optional.of("another review"))).isEmpty();
            leased.set(0);
            stopped.get(5, TimeUnit.SECONDS);
        } finally {
            leased.set(0);
            stopped.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldResumeAfterAContextPauseWithoutAClosedExecutorOrLatchedDrain() {
        AgentReviewDrain drain = drain(Duration.ZERO);
        drain.start();
        assertThat(drain.claim(() -> Optional.of("first"))).contains("first");
        drain.stop();
        assertThat(drain.claim(() -> Optional.of("paused"))).isEmpty();
        drain.start();
        assertThat(drain.claim(() -> Optional.of("resumed"))).contains("resumed");
        drain.stop();
    }

    @Test
    void shouldNotClaimBeforeReadinessEvenWhenThePrivateGatewayIsRoutable() {
        AgentReviewDrain drain = drain(Duration.ZERO);
        drain.start();
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertThat(drain.claim(() -> Optional.of("not ready"))).isEmpty();
        drain.stop();
    }

    @Test
    void shouldFinishShutdownAtItsBoundWithoutRewritingTheRemainingRun() {
        AgentReviewDrain drain = drain(Duration.ZERO);
        when(runs.leasedReviews()).thenReturn(1);
        drain.start();
        assertThat(drain.claim(() -> Optional.of("before shutdown"))).isPresent();
        drain.stop();
        assertThat(drain.isRunning()).isFalse();
        assertThat(drain.claim(() -> Optional.of("late"))).isEmpty();
    }
}
