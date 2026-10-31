package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.integration.core.metrics.ReviewRefusalRecorder;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class ReviewJobTelemetry {

    enum Phase {
        QUEUE("queue"),
        EXECUTION("execution"),
        TOTAL("total");

        private final String tag;

        Phase(String tag) {
            this.tag = tag;
        }
    }

    enum ResultOutcome {
        STARTED("started"),
        COMPLETED("completed"),
        FAILED("failed"),
        TIMED_OUT("timed_out"),
        CANCELLED("cancelled");

        private final String tag;

        ResultOutcome(String tag) {
            this.tag = tag;
        }

        static ResultOutcome terminal(ReviewJobStatus status) {
            return switch (status) {
                case COMPLETED -> COMPLETED;
                case FAILED -> FAILED;
                case TIMED_OUT -> TIMED_OUT;
                case CANCELLED -> CANCELLED;
                default -> throw new IllegalArgumentException("Not a terminal agent-job status: " + status);
            };
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ReviewJobTelemetry.class);
    private final Map<Phase, Timer> durations = new EnumMap<>(Phase.class);
    private final Map<ResultOutcome, Counter> terminalCounters = new EnumMap<>(ResultOutcome.class);

    private final Tracer tracer;
    private final MeterRegistry registry;
    private final Counter concurrencyRejected;
    private final Timer claimLatency;

    ReviewJobTelemetry(MeterRegistry registry, Tracer tracer) {
        this.tracer = tracer;
        this.registry = registry;
        concurrencyRejected = Counter.builder(AgentMetrics.AGENT_JOB_CONCURRENCY_REJECTED)
                .description("Jobs rejected due to concurrency limits")
                .register(registry);
        claimLatency = Timer.builder(AgentMetrics.AGENT_JOB_CLAIM_LATENCY)
                .description("Time between job submission and its claim")
                .register(registry);
        for (Phase phase : Phase.values()) {
            durations.put(
                    phase,
                    Timer.builder(AgentMetrics.AGENT_JOB_DURATION)
                            .description("Agent job lifecycle phase duration")
                            .tag("phase", phase.tag)
                            .publishPercentileHistogram()
                            .register(registry));
        }
        for (ResultOutcome outcome : new ResultOutcome[] {
            ResultOutcome.COMPLETED, ResultOutcome.FAILED, ResultOutcome.TIMED_OUT, ResultOutcome.CANCELLED
        }) {
            terminalCounters.put(
                    outcome,
                    Counter.builder(AgentMetrics.AGENT_JOB_TOTAL)
                            .description("Agent jobs reaching a terminal state")
                            .tag("outcome", outcome.tag)
                            .register(registry));
        }
    }

    void claimRejected() {
        concurrencyRejected.increment();
    }

    void claimWait(Duration duration) {
        claimLatency.record(duration);
    }

    void claimRefused(String reason) {
        ReviewRefusalRecorder.record(registry, "execution", reason);
    }

    void budgetHeld() {
        registry.counter(AgentMetrics.AGENT_JOB_BUDGET_HELD).increment();
    }

    void budgetRefused() {
        registry.counter(AgentMetrics.AGENT_JOB_BUDGET_REFUSED).increment();
    }

    void modelRefused() {
        registry.counter(AgentMetrics.AGENT_JOB_MODEL_REFUSED).increment();
    }

    Span startExecution(ReviewJob job) {
        // A persisted job can run long after its submitting request, or on another worker. Start
        // a real execution trace and retain the submission correlation separately, not a fake parent.
        return tracer.spanBuilder()
                .setNoParent()
                .name("practice_review.execute")
                .tag("hephaestus.job.id", job.getId().toString())
                .tag("hephaestus.workspace.id", Long.toString(job.getWorkspaceId()))
                .tag("hephaestus.job.attempt", job.getAttempt())
                .tag("hephaestus.job.submission_trace_id", job.getTraceId())
                .start();
    }

    Tracer.SpanInScope executionScope(Span span) {
        return tracer.withSpan(span);
    }

    void transition(ReviewJob job, String event, Phase phase, ResultOutcome outcome, Duration duration) {
        log.atInfo()
                .addKeyValue("event.name", event)
                .addKeyValue(StructuredLogKeys.JOB_ID, job.getId())
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, job.getWorkspaceId())
                .addKeyValue("review.id", job.getReviewId())
                .addKeyValue("job.phase", phase.tag)
                .addKeyValue("job.outcome", outcome.tag)
                .addKeyValue("duration.ms", duration.toMillis())
                .log("Agent job lifecycle transition");

        record(phase, duration);
    }

    void record(Phase phase, Duration duration) {
        Objects.requireNonNull(durations.get(phase)).record(duration);
    }

    void terminal(ReviewJob job, ReviewJobStatus status, Duration duration) {
        ResultOutcome outcome = ResultOutcome.terminal(status);
        transition(job, "agent.job.terminal", Phase.TOTAL, outcome, duration);
        Objects.requireNonNull(terminalCounters.get(outcome)).increment();
    }

    void preparationFailed(ReviewJob job, PreparationFailure failure, String result) {
        log.atWarn()
                .addKeyValue("event.name", "agent.job.preparation_failed")
                .addKeyValue(StructuredLogKeys.JOB_ID, job.getId())
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, job.getWorkspaceId())
                .addKeyValue("review.id", job.getReviewId())
                .addKeyValue("job.preparation.stage", failure.stage().tag())
                .addKeyValue("job.preparation.kind", failure.kind().tag())
                .addKeyValue("job.preparation.reason", failure.reasonTag())
                .addKeyValue("exception.type", failure.exceptionTypes().getFirst())
                .addKeyValue(
                        "exception.cause.types",
                        failure.exceptionTypes()
                                .subList(1, failure.exceptionTypes().size()))
                .addKeyValue("job.result", result)
                .log("Agent job preparation failed");
        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag("hephaestus.job.preparation.stage", failure.stage().tag());
            span.tag("hephaestus.job.preparation.kind", failure.kind().tag());
            span.tag("hephaestus.job.preparation.reason", failure.reasonTag());
        }
    }

    static Duration age(ReviewJob job) {
        Instant createdAt = job.getSubmittedAt();
        if (createdAt == null) return Duration.ZERO;
        Duration age = Duration.between(createdAt, Instant.now());
        return age.isNegative() ? Duration.ZERO : age;
    }
}
