package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunNotifications;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgentNextWaitsTest {

    private static final Set<AgentRunKind> REVIEW = Set.of(AgentRunKind.REVIEW);
    private static final Duration LONG = Duration.ofMinutes(5);

    private final AgentRuns runs = mock(AgentRuns.class);
    private final AgentRunNotifications notifications = mock(AgentRunNotifications.class);
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        requests.shutdownNow();
    }

    private AgentNextWaits started(int capacity) {
        var waits = new AgentNextWaits(runs, notifications, capacity, LONG, LONG);
        waits.start();
        return waits;
    }

    private static AgentSandboxes.LiveSandbox sandbox(String subject) {
        return new AgentSandboxes.LiveSandbox(UUID.randomUUID(), 1L, subject, AgentSandboxKind.REVIEW);
    }

    /** A request whose claim never finds a run, and how often it claimed. */
    private CompletableFuture<Optional<String>> next(
            AgentNextWaits waits, AgentSandboxes.LiveSandbox sandbox, AtomicInteger claims) {
        return CompletableFuture.supplyAsync(
                () -> waits.claim(sandbox, REVIEW, () -> {
                    claims.incrementAndGet();
                    return Optional.<String>empty();
                }),
                requests);
    }

    private static void waitFor(AgentNextWaits waits, int requests) {
        await().atMost(Duration.ofSeconds(10)).until(() -> waits.waiting() == requests);
    }

    @Test
    void shouldEndTheEarlierWaitWhenTheSameSandboxAsksAgain() throws Exception {
        var waits = started(4);
        var sandbox = sandbox("review:a");
        var earlier = next(waits, sandbox, new AtomicInteger());
        waitFor(waits, 1);

        var later = next(waits, sandbox, new AtomicInteger());

        assertThat(earlier.get(5, TimeUnit.SECONDS)).isEmpty();
        waitFor(waits, 1);
        assertThat(later).isNotDone();
    }

    @Test
    void shouldAnswerEveryWaitingRequestAndClaimNoMoreWhenItStops() throws Exception {
        var waits = started(4);
        var claims = new AtomicInteger();
        var answer = next(waits, sandbox("review:a"), claims);
        waitFor(waits, 1);

        waits.stop();

        assertThat(answer.get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(claims).hasValue(1);
        assertThat(waits.claim(sandbox("review:b"), REVIEW, () -> {
                    claims.incrementAndGet();
                    return Optional.of("leased");
                }))
                .contains("leased");
        assertThat(waits.waiting()).isZero();
    }

    @Test
    void shouldClaimOnceAndAnswerAtOnceWhenEveryWaitIsTaken() throws Exception {
        var waits = started(1);
        var held = next(waits, sandbox("review:a"), new AtomicInteger());
        waitFor(waits, 1);
        var claims = new AtomicInteger();

        assertThat(next(waits, sandbox("review:b"), claims).get(5, TimeUnit.SECONDS))
                .isEmpty();
        assertThat(claims).hasValue(1);
        waits.stop();
        assertThat(held.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void shouldWakeOnlyTheRequestsWhoseSubjectHasADueRunOfTheirKinds() throws Exception {
        var waits = started(4);
        var due = sandbox("review:due");
        var dueClaims = new AtomicInteger();
        var otherClaims = new AtomicInteger();
        var dueAnswer = next(waits, due, dueClaims);
        var otherAnswer = next(waits, sandbox("review:other"), otherClaims);
        // Each request made its first claim, so a second claim can come only from a wake.
        await().atMost(Duration.ofSeconds(5)).until(() -> dueClaims.get() >= 1 && otherClaims.get() >= 1);
        when(runs.dueOf(any(), any(), any()))
                .thenReturn(List.of(new AgentRuns.DueRun(
                        new AgentRunRef(UUID.randomUUID(), 1L), AgentRunKind.REVIEW, "review:due")));

        waits.pass();

        await().atMost(Duration.ofSeconds(5)).untilAtomic(dueClaims, greaterThanOrEqualTo(2));
        assertThat(otherClaims).hasValue(1);
        waits.stop();
        assertThat(dueAnswer.get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(otherAnswer.get(5, TimeUnit.SECONDS)).isEmpty();
    }
}
