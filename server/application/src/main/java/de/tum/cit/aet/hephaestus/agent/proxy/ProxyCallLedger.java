package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelUnavailableException;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.job.FrozenCharge;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder.CallUsage;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder.OpenCall;
import de.tum.cit.aet.hephaestus.agent.usage.ModelCallFailure;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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

    private final LlmAdmissionService admission;
    private final ProductFeedbackDraftCalls drafts;
    private final LlmUsageRecorder recorder;
    private final AgentRuns runs;
    private final ReviewJobRepository jobs;
    private final ConversationTurnAccess turns;
    private final ProxyBudgetGate budgetGate;
    private final ObjectMapper mapper;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate ownTransaction;

    ProxyCallLedger(
            LlmAdmissionService admission,
            ProductFeedbackDraftCalls drafts,
            LlmUsageRecorder recorder,
            AgentRuns runs,
            ReviewJobRepository jobs,
            ConversationTurnAccess turns,
            ProxyBudgetGate budgetGate,
            ObjectMapper mapper,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.admission = admission;
        this.drafts = drafts;
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
     * @param practiceId the practice that the review sandbox names for the call; the row keeps it only when the call's
     *     job measures that practice
     * @throws ClaimLost when the claim no longer holds its run
     * @throws BudgetHeld when the call's bound does not fit under its purse's cap
     */
    UUID open(ProxyRouting routing, Bound bound, String upstreamModel, @Nullable Long practiceId) {
        var attempt = routing.attempt();

        if (attempt.sourceType() == UsageSourceType.PRODUCT_FEEDBACK_DRAFT) return draftCall(routing, bound);
        if (attempt.sourceType() == UsageSourceType.PROBE) return probeCall(routing, bound, upstreamModel);
        long workspaceId = Objects.requireNonNull(routing.workspaceId(), "a billed call has a workspace");
        UUID runId = Objects.requireNonNull(attempt.runId(), "a billed call has a run");
        UUID call;
        try {
            call = Objects.requireNonNull(ownTransaction.execute(status -> {
                runs.requireCurrent(new AgentRunFence(runId, workspaceId, attempt.epoch()));
                OpenCall open = attempt.sourceType() == UsageSourceType.CONVERSATION_TURN
                        ? turnCall(attempt, workspaceId, runId, bound)
                        : reviewCall(attempt, workspaceId, runId, bound, practiceId);
                // The bound that the row stores is the one held against the cap.
                if (budgetGate.refusesHold(routing, open.costBoundUsd())) throw new BudgetHeld();
                return recorder.open(open);
            }));
        } catch (StaleRunException lost) {
            throw new ClaimLost(lost);
        }
        budgetGate.spent(workspaceId);
        if (attempt.sourceType() == UsageSourceType.CONVERSATION_TURN)
            turnCallAdmitted(workspaceId, attempt.sourceId());
        return call;
    }

    private UUID draftCall(ProxyRouting routing, Bound bound) {
        var attempt = routing.attempt();
        UUID runId = Objects.requireNonNull(attempt.runId());
        long modelId = Objects.requireNonNull(routing.modelId());
        try {
            return Objects.requireNonNull(ownTransaction.execute(status -> {
                // The destination and filing precede the run lock, as in a claim and its end.
                if (!drafts.admitCall(attempt.sourceId(), attempt.number(), modelId)) throw new BudgetHeld();
                runs.requireCurrent(new AgentRunFence(runId, AgentRunRef.INSTANCE_SCOPE, attempt.epoch()));
                if (recorder.hasReportDraftCall(runId, attempt.epoch())) throw new BudgetHeld();
                var charge = admission.admitInstance(modelId);
                return recorder.open(new OpenCall(
                        null,
                        runId,
                        attempt.epoch(),
                        "REPORT_DRAFT",
                        UsageSourceType.PRODUCT_FEEDBACK_DRAFT,
                        attempt.sourceId(),
                        attempt.number(),
                        null,
                        ModelKind.CHAT,
                        null,
                        charge.model(),
                        charge.price(),
                        costBound(charge.price(), bound)));
            }));
        } catch (StaleRunException lost) {
            throw new ClaimLost(lost);
        }
    }

    /** After the call's row committed, so a failure here cannot refuse the call: readers only see the change late. */
    private void turnCallAdmitted(long workspaceId, UUID turnId) {
        try {
            turns.modelCallAdmitted(workspaceId, turnId);
        } catch (RuntimeException failed) {
            log.warn(
                    "A conversation turn's admitted model call was not published: turnId={}, error={}",
                    turnId,
                    failed.getClass().getSimpleName());
        }
    }

    /** Names the practice whose review session makes the call (pi-provider.ts). */
    static final String PRACTICE_HEADER = "X-Agent-Practice";

    /** A positive decimal id that a long holds. */
    private static final Pattern PRACTICE_ID = Pattern.compile("[1-9][0-9]{0,17}");

    /** The practice that a review sandbox names for its call; null when the header is absent or no positive id. */
    static @Nullable Long namedPractice(HttpHeaders headers) {
        String named = headers.getFirst(PRACTICE_HEADER);
        if (named == null || !PRACTICE_ID.matcher(named).matches()) return null;
        return Long.parseLong(named);
    }

    /**
     * Where the practice that a review sandbox names for its call stands in its job's start order, which is the order of
     * the job's assignments. Null for any other call, and for a practice that the job does not measure.
     */
    @Nullable
    Integer sessionRank(ProxyRouting routing, @Nullable Long practiceId) {
        Long workspaceId = routing.workspaceId();
        if (practiceId == null || workspaceId == null || routing.attempt().sourceType() != UsageSourceType.REVIEW_JOB)
            return null;
        return jobs.findCall(routing.attempt().sourceId(), workspaceId)
                .map(job -> rankIn(job.input().assignments(), practiceId))
                .orElse(null);
    }

    /** The practice's place in a job's assignments; null for a practice that the job does not measure. */
    private static @Nullable Integer rankIn(List<ReviewAssignmentId> assigned, @Nullable Long practiceId) {
        if (practiceId == null) return null;
        for (int rank = 0; rank < assigned.size(); rank++)
            if (assigned.get(rank).practiceId() == practiceId) return rank;
        return null;
    }

    private UUID probeCall(ProxyRouting routing, Bound bound, String upstreamModel) {
        UUID call = Objects.requireNonNull(ownTransaction.execute(status -> {
            var attempt = routing.attempt();
            if (attempt.runId() != null || attempt.number() != 0)
                throw new IllegalArgumentException("A capability probe has no run or action");
            var charge = admission.admitProbe(new LlmModelResolver.ConnectionRef(
                    routing.connectionScope(), routing.connectionId(), routing.modelId(), routing.workspaceId()));
            if (!charge.model().equals(upstreamModel))
                throw new LlmModelUnavailableException("The probe model changed before the call started");
            var cost = costBound(charge.price(), bound);
            if (budgetGate.refusesHold(routing, cost)) throw new BudgetHeld();
            return recorder.open(new OpenCall(
                    routing.workspaceId(),
                    null,
                    null,
                    "MODEL_CAPABILITY_PROBE",
                    UsageSourceType.PROBE,
                    attempt.sourceId(),
                    0,
                    null,
                    ModelKind.CHAT,
                    null,
                    charge.model(),
                    charge.price(),
                    cost,
                    null,
                    recorder.recoveryDeadline(LlmModelProbeGateway.CALL_TIMEOUT),
                    routing.connectionScope() == UsageFundingSource.INSTANCE ? routing.modelId() : null));
        }));
        if (routing.workspaceId() != null) budgetGate.spent(routing.workspaceId());
        return call;
    }

    /** The most that the call can cost at its frozen price. */
    static @Nullable BigDecimal costBound(LlmPriceSnapshot price, Bound bound) {
        Long output = bound.outputTokens();
        return output == null ? null : price.upperBound(bound.inputBytes(), output);
    }

    private OpenCall turnCall(ProxyRouting.BilledAttempt attempt, long workspaceId, UUID runId, Bound bound) {
        var admission = turns.find(workspaceId, attempt.sourceId())
                .flatMap(turn -> ConversationTurnAdmission.read(turn.admission()))
                .orElse(null);
        FrozenCharge charge =
                admission == null ? FrozenCharge.UNKNOWN : new FrozenCharge(admission.model(), admission.price());
        return new OpenCall(
                workspaceId,
                runId,
                attempt.epoch(),
                UsageSourceType.CONVERSATION_TURN.name(),
                UsageSourceType.CONVERSATION_TURN,
                attempt.sourceId(),
                attempt.number(),
                null,
                ModelKind.CHAT,
                null,
                charge.model(),
                charge.price(),
                costBound(charge.price(), bound));
    }

    private OpenCall reviewCall(
            ProxyRouting.BilledAttempt attempt, long workspaceId, UUID runId, Bound bound, @Nullable Long practiceId) {
        var job = jobs.findCall(attempt.sourceId(), workspaceId)
                .filter(found -> found.runId().equals(runId))
                .orElseThrow(() -> new ClaimLost(new IllegalStateException("The call's job moved to another run")));
        ConfigSnapshot snapshot = ConfigSnapshot.fromJson(job.configSnapshot(), mapper);
        FrozenCharge charge = FrozenCharge.of(snapshot.model());
        return new OpenCall(
                workspaceId,
                runId,
                attempt.epoch(),
                "PRACTICE_REVIEW",
                UsageSourceType.REVIEW_JOB,
                job.id(),
                attempt.number(),
                job.reviewId(),
                ModelKind.CHAT,
                // The sandbox names the practice, so only a practice of the job's own assignments is kept.
                rankIn(job.input().assignments(), practiceId) == null ? null : practiceId,
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
    void complete(ProxyRouting routing, UUID call, @Nullable CallUsage usage, @Nullable ModelCallFailure failure) {
        Long workspaceId = routing.workspaceId();
        try {
            ownTransaction.executeWithoutResult(status -> {
                if (routing.attempt().sourceType() == UsageSourceType.PRODUCT_FEEDBACK_DRAFT)
                    drafts.lockForUsage(routing.sourceId());
                boolean measured = recorder.complete(call, workspaceId, usage, failure);
                if (measured
                        && usage != null
                        && routing.attempt().sourceType() == UsageSourceType.PRODUCT_FEEDBACK_DRAFT)
                    drafts.count(
                            routing.sourceId(),
                            usage.inputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens(),
                            usage.outputTokens());
            });
            if (workspaceId != null) budgetGate.spent(workspaceId);
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
