package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The accumulator bean is absent from the test context ({@code hephaestus.runtime.worker.enabled=false}),
 * so it is constructed directly here and its {@code REQUIRES_NEW} boundary supplied by
 * {@link TransactionTemplate}.
 */
class MentorTurnUsageAccumulatorIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private SimpleMeterRegistry meters;
    private MentorTurnUsageAccumulator accumulator;
    private Workspace workspace;
    private MentorConversation conversation;

    @BeforeEach
    void setUpFixture() {
        meters = new SimpleMeterRegistry();
        accumulator = new MentorTurnUsageAccumulator(turnRepository, meters);

        User owner = persistUser("mentor-usage-owner-" + UUID.randomUUID());
        workspace = createWorkspace(
                "mentor-usage-" + UUID.randomUUID().toString().substring(0, 8),
                "Mentor Usage",
                "mentor-usage-org",
                AccountType.ORG,
                owner);
        conversation = conversations.findOrCreateMain(workspace.getId(), owner.getId(), Set.of(owner.getId()));
    }

    /** A turn a worker claimed, so its first attempt holds the lease. */
    private MentorTurn runningTurn() {
        turns.submit(
                workspace.getId(),
                conversation.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        "How did my last review go?",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        return turns.claimNext("worker-usage", Duration.ofMinutes(5)).orElseThrow();
    }

    private ProxyRouting.BilledAttempt attempt(MentorTurn turn) {
        return new ProxyRouting.BilledAttempt(
                LlmUsageSourceType.MENTOR_TURN, turn.getId(), turn.getAttempt(), BigDecimal.ZERO, "worker-1");
    }

    private void accumulate(ProxyRouting.BilledAttempt attempt, ProxyTokenUsage usage) {
        transactionTemplate.executeWithoutResult(tx -> accumulator.accumulate(attempt, usage));
    }

    private MentorTurn stored(MentorTurn turn) {
        return turnRepository.findById(turn.getId()).orElseThrow();
    }

    @Test
    @DisplayName("successive calls add onto the attempt's row rather than replacing it")
    void shouldAccumulateRepeatedCallsWhenTheAttemptHoldsItsLease() {
        MentorTurn turn = runningTurn();

        accumulate(attempt(turn), new ProxyTokenUsage(100, 40, 10, 25, 15));
        accumulate(attempt(turn), new ProxyTokenUsage(200, 60, 5, 0, 0));
        accumulate(attempt(turn), new ProxyTokenUsage(300, 80, 0, 0, 0));

        MentorTurn row = stored(turn);
        assertThat(row.getLlmCalls()).isEqualTo(3);
        assertThat(row.getInputTokens()).isEqualTo(600);
        assertThat(row.getOutputTokens()).isEqualTo(180);
        assertThat(row.getReasoningTokens()).isEqualTo(15);
        assertThat(row.getCachedTokens()).isEqualTo(25);
        assertThat(row.getCacheWriteTokens()).isEqualTo(15);
    }

    @Test
    @DisplayName("a call that lands after the attempt ended is dropped, not added to an attempt already billed")
    void shouldDropACallWhenTheAttemptAlreadyEnded() {
        MentorTurn turn = runningTurn();
        accumulate(attempt(turn), new ProxyTokenUsage(100, 40, 0, 0, 0));

        turns.end(turn.getId(), turn.getAttempt(), "worker-usage", MentorTurn.Status.DONE, List.of(), null);
        accumulate(attempt(turn), new ProxyTokenUsage(999, 999, 0, 0, 0));

        MentorTurn row = stored(turn);
        assertThat(row.getLlmCalls()).isEqualTo(1);
        assertThat(row.getInputTokens()).isEqualTo(100);
        assertThat(meters.counter("llm.proxy.usage.mentor.superseded").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a call authenticated for another attempt is dropped")
    void shouldDropACallWhenItsAttemptNumberIsNotTheRunningOne() {
        MentorTurn turn = runningTurn();
        var stale = new ProxyRouting.BilledAttempt(
                LlmUsageSourceType.MENTOR_TURN, turn.getId(), turn.getAttempt() + 1, BigDecimal.ZERO, "worker-1");

        accumulate(stale, new ProxyTokenUsage(500, 5, 0, 0, 0));

        assertThat(stored(turn).getLlmCalls()).isZero();
        assertThat(meters.counter("llm.proxy.usage.mentor.superseded").count()).isEqualTo(1.0);
    }
}
