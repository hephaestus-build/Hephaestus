package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.job.FrozenCharge;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder.CallUsage;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder.OpenCall;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes each proxied model call to the usage ledger ({@link LlmUsageRecorder}): opens its row before the call leaves
 * and measures it when the call ends. Each write is its own transaction: the proxy servlet thread has no ambient
 * transaction, the open row must commit before the provider works, and the measurement must commit whatever the
 * passthrough does.
 */
@Component
@ConditionalOnAgentRuntime
class ProxyCallLedger {

    private static final Logger log = LoggerFactory.getLogger(ProxyCallLedger.class);

    /** The call's claim no longer holds its run, so the call must not start. */
    static final class ClaimLost extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        ClaimLost(Throwable cause) {
            super("The call's claim no longer holds its run", cause);
        }
    }

    /** The call's upper bound does not fit under its purse's monthly cap, so the call must not start. */
    static final class BudgetHeld extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        BudgetHeld() {
            super("The call's upper bound does not fit under its purse's monthly cap");
        }
    }

    private final LlmUsageRecorder recorder;
    private final AgentRuns runs;
    private final ReviewJobRepository jobs;
    private final MentorTurnRepository turns;
    private final ProxyBudgetGate budgetGate;
    private final ObjectMapper mapper;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate ownTransaction;

    ProxyCallLedger(
            LlmUsageRecorder recorder,
            AgentRuns runs,
            ReviewJobRepository jobs,
            MentorTurnRepository turns,
            ProxyBudgetGate budgetGate,
            ObjectMapper mapper,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.recorder = recorder;
        this.runs = runs;
        this.jobs = jobs;
        this.turns = turns;
        this.budgetGate = budgetGate;
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * What bounds one call's cost.
     *
     * @param inputBytes the bytes of the call's request, which bound its input tokens
     * @param outputTokens the output tokens that the call may write; {@code null} when nothing bounds them
     */
    record Bound(long inputBytes, @Nullable Long outputTokens) {}

    /**
     * Opens the call's row with the model and rates that the call's claim froze for its model kind, while the claim
     * holds its run: the claim's end then settles the row, also when this replica stops during the call. The row holds
     * the call's upper bound, which every spend read counts until the call is measured. The row opens only while that
     * bound fits under its purse's cap ({@link ProxyBudgetGate#refusesHold}). The budget gate of this replica sees the
     * bound at once.
     *
     * @throws ClaimLost when the claim no longer holds its run
     * @throws BudgetHeld when the call's bound does not fit under its purse's cap
     */
    UUID open(ProxyRouting routing, Bound bound) {
        var attempt = routing.attempt();
        long workspaceId = Objects.requireNonNull(routing.workspaceId(), "a billed call has a workspace");
        UUID runId = Objects.requireNonNull(attempt.runId(), "a billed call has a run");
        UUID call;
        try {
            call = Objects.requireNonNull(ownTransaction.execute(status -> {
                runs.requireCurrent(new AgentRunFence(runId, workspaceId, attempt.epoch()));
                OpenCall open = attempt.sourceType() == UsageSourceType.MENTOR_TURN
                        ? turnCall(attempt, workspaceId, runId, bound)
                        : reviewCall(attempt, workspaceId, runId, bound);
                // The bound that the row stores is the one held against the cap.
                if (budgetGate.refusesHold(routing, open.costBoundUsd())) throw new BudgetHeld();
                return recorder.open(open);
            }));
        } catch (StaleRunException lost) {
            throw new ClaimLost(lost);
        }
        budgetGate.spent(workspaceId);
        return call;
    }

    /** The most that the call can cost at its frozen price. */
    static @Nullable BigDecimal costBound(LlmPriceSnapshot price, Bound bound) {
        Long output = bound.outputTokens();
        return output == null ? null : price.upperBound(bound.inputBytes(), output);
    }

    private OpenCall turnCall(ProxyRouting.BilledAttempt attempt, long workspaceId, UUID runId, Bound bound) {
        var admission = turns.findByIdAndWorkspaceId(attempt.sourceId(), workspaceId)
                .flatMap(turn -> MentorTurnAdmission.read(turn.getAdmission()))
                .orElse(null);
        FrozenCharge charge =
                admission == null ? FrozenCharge.UNKNOWN : new FrozenCharge(admission.model(), admission.price());
        return new OpenCall(
                workspaceId,
                runId,
                attempt.epoch(),
                UsageSourceType.MENTOR_TURN.name(),
                UsageSourceType.MENTOR_TURN,
                attempt.sourceId(),
                attempt.number(),
                null,
                ModelKind.CHAT,
                null,
                charge.model(),
                charge.price(),
                costBound(charge.price(), bound));
    }

    private OpenCall reviewCall(ProxyRouting.BilledAttempt attempt, long workspaceId, UUID runId, Bound bound) {
        var job = jobs.findByIdAndWorkspaceId(attempt.sourceId(), workspaceId)
                .filter(found -> found.getRunId().equals(runId))
                .orElseThrow(() -> new ClaimLost(new IllegalStateException("The call's job moved to another run")));
        ConfigSnapshot snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), mapper);
        FrozenCharge charge = FrozenCharge.of(snapshot.model());
        return new OpenCall(
                workspaceId,
                runId,
                attempt.epoch(),
                "PRACTICE_REVIEW",
                UsageSourceType.REVIEW_JOB,
                job.getId(),
                attempt.number(),
                job.getReviewId(),
                ModelKind.CHAT,
                null,
                charge.model(),
                charge.price(),
                costBound(charge.price(), bound));
    }

    /**
     * Measures the call when it ends. Never throws, so accounting never breaks the proxied response: a lost
     * measurement leaves the row open until its claim's end settles it unverifiable, and is counted. The budget gate
     * of this replica sees the call's cost at once.
     *
     * @param usage {@code null} when the provider reported no usage or the call ended without an answer
     */
    void complete(ProxyRouting routing, UUID call, @Nullable CallUsage usage) {
        long workspaceId = Objects.requireNonNull(routing.workspaceId(), "a billed call has a workspace");
        try {
            ownTransaction.executeWithoutResult(status -> recorder.complete(call, workspaceId, usage));
            budgetGate.spent(workspaceId);
        } catch (RuntimeException e) {
            log.warn("Lost the measurement of model call {}; its claim's end settles it unverifiable", call, e);
            meterRegistry
                    .counter(AgentMetrics.LLM_PROXY_USAGE_ACCUMULATE_FAILURE)
                    .increment();
        }
    }

    static CallUsage usage(ProxyTokenUsage usage) {
        return new CallUsage(
                usage.billableInputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                usage.reasoningTokens(),
                0);
    }
}
