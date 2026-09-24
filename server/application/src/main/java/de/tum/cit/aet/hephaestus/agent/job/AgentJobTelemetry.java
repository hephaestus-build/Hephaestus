package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
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
final class AgentJobTelemetry {

    enum Phase {
        QUEUE("queue"),
        EXECUTION("execution"),
        DELIVERY("delivery"),
        TOTAL("total");

        private final String tag;

        Phase(String tag) {
            this.tag = tag;
        }
    }

    enum Outcome {
        STARTED("started"),
        DELIVERED("delivered"),
        DELIVERY_FAILED("failed"),
        COMPLETED("completed"),
        FAILED("failed"),
        TIMED_OUT("timed_out"),
        CANCELLED("cancelled");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        static Outcome terminal(AgentJobStatus status) {
            return switch (status) {
                case COMPLETED -> COMPLETED;
                case FAILED -> FAILED;
                case TIMED_OUT -> TIMED_OUT;
                case CANCELLED -> CANCELLED;
                default -> throw new IllegalArgumentException("Not a terminal agent-job status: " + status);
            };
        }
    }

    private static final Logger log = LoggerFactory.getLogger(AgentJobTelemetry.class);
    private final Map<Phase, Timer> durations = new EnumMap<>(Phase.class);
    private final Map<Outcome, Counter> terminalCounters = new EnumMap<>(Outcome.class);

    private final Tracer tracer;

    AgentJobTelemetry(MeterRegistry registry, Tracer tracer) {
        this.tracer = tracer;
        for (Phase phase : Phase.values()) {
            durations.put(
                    phase,
                    Timer.builder(AgentMetrics.AGENT_JOB_DURATION)
                            .description("Agent job lifecycle phase duration")
                            .tag("phase", phase.tag)
                            .publishPercentileHistogram()
                            .register(registry));
        }
        for (Outcome outcome :
                new Outcome[] {Outcome.COMPLETED, Outcome.FAILED, Outcome.TIMED_OUT, Outcome.CANCELLED}) {
            terminalCounters.put(
                    outcome,
                    Counter.builder(AgentMetrics.AGENT_JOB_TOTAL)
                            .description("Agent jobs reaching a terminal state")
                            .tag("outcome", outcome.tag)
                            .register(registry));
        }
    }

    Span startExecution(AgentJob job) {
        // A persisted job can run long after its submitting request, or on another worker. Start
        // a real execution trace and retain the submission correlation separately, not a fake parent.
        return tracer.spanBuilder()
                .setNoParent()
                .name("practice_review.execute")
                .tag("hephaestus.job.id", job.getId().toString())
                .tag("hephaestus.workspace.id", job.getWorkspace().getId().toString())
                .tag("hephaestus.job.attempt", job.getRetryCount())
                .tag("hephaestus.job.submission_trace_id", job.getTraceId())
                .start();
    }

    Tracer.SpanInScope executionScope(Span span) {
        return tracer.withSpan(span);
    }

    static void queued(AgentJob job) {
        log.atInfo()
                .addKeyValue("event.name", "agent.job.queued")
                .addKeyValue(StructuredLogKeys.JOB_ID, job.getId())
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, job.getWorkspace().getId())
                .addKeyValue("job.type", job.getJobType())
                .addKeyValue("job.phase", Phase.QUEUE.tag)
                .addKeyValue("job.outcome", "queued")
                .addKeyValue("duration.ms", 0)
                .log("Agent job lifecycle transition");
    }

    void transition(AgentJob job, String event, Phase phase, Outcome outcome, Duration duration) {
        log.atInfo()
                .addKeyValue("event.name", event)
                .addKeyValue(StructuredLogKeys.JOB_ID, job.getId())
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, job.getWorkspace().getId())
                .addKeyValue("job.type", job.getJobType())
                .addKeyValue("job.phase", phase.tag)
                .addKeyValue("job.outcome", outcome.tag)
                .addKeyValue("duration.ms", duration.toMillis())
                .log("Agent job lifecycle transition");

        record(phase, duration);
    }

    void record(Phase phase, Duration duration) {
        Objects.requireNonNull(durations.get(phase)).record(duration);
    }

    void terminal(AgentJob job, AgentJobStatus status, Duration duration) {
        Outcome outcome = Outcome.terminal(status);
        transition(job, "agent.job.terminal", Phase.TOTAL, outcome, duration);
        Objects.requireNonNull(terminalCounters.get(outcome)).increment();
    }

    static Duration age(AgentJob job) {
        Instant createdAt = job.getCreatedAt();
        if (createdAt == null) return Duration.ZERO;
        Duration age = Duration.between(createdAt, Instant.now());
        return age.isNegative() ? Duration.ZERO : age;
    }
}
