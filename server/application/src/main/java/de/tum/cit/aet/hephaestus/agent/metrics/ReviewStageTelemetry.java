package de.tum.cit.aet.hephaestus.agent.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How long each stage of a review attempt takes: one timer with a bounded {@code stage} and {@code outcome}, and one
 * span per stage, a child of the span that is current when the stage starts.
 *
 * <p>The stages do not add up to the attempt. Composition includes its wait for a composition slot, and a resumed
 * attempt has no capture and no sandbox stage. Admission is the runner's requests to the server, which
 * {@code http.server.requests} already times by route. Each owner builds its own instance from the shared registry,
 * which hands both the same timers.
 */
public final class ReviewStageTelemetry {

    private static final Logger log = LoggerFactory.getLogger(ReviewStageTelemetry.class);

    /** A capture takes seconds; a sandbox can run for its whole deadline. */
    private static final Duration[] BOUNDS = {
        Duration.ofMillis(100),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        Duration.ofSeconds(30),
        Duration.ofMinutes(1),
        Duration.ofMinutes(2),
        Duration.ofMinutes(5),
        Duration.ofMinutes(10),
        Duration.ofMinutes(15),
        Duration.ofMinutes(20),
        Duration.ofMinutes(30),
        Duration.ofHours(1)
    };

    public enum Stage {
        /** The evidence capture and the inputs of the practices, before the sandbox. */
        CAPTURE,
        /** The sandbox that runs the practice sessions, from its launch to its result. */
        SANDBOX,
        /** The composition of the feedback, from the claim of its run in the review's sandbox to the run's end. */
        COMPOSITION;

        final String tag = name().toLowerCase(Locale.ROOT);
    }

    public enum Outcome {
        OK,
        FAILED,
        /** The sandbox left a practice without a result and staged the rest; another attempt may continue it. */
        PARTIAL,
        /** The composition stopped for a passing cause, and another attempt continues it. */
        HANDED_ON;

        final String tag = name().toLowerCase(Locale.ROOT);
    }

    private final Map<Stage, Map<Outcome, Timer>> timers = new EnumMap<>(Stage.class);
    private final Tracer tracer;

    public ReviewStageTelemetry(MeterRegistry registry, Tracer tracer) {
        this.tracer = tracer;
        for (Stage stage : Stage.values()) {
            Map<Outcome, Timer> byOutcome = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                byOutcome.put(
                        outcome,
                        Timer.builder(AgentMetrics.AGENT_JOB_STAGE_DURATION)
                                .description("Duration of one stage of a review attempt")
                                .tag("stage", stage.tag)
                                .tag("outcome", outcome.tag)
                                .serviceLevelObjectives(BOUNDS)
                                .register(registry));
            }
            timers.put(stage, byOutcome);
        }
    }

    /** Records a stage that spans requests, such as the run that composes a review, once it ended. */
    public void record(Stage stage, Duration duration, Outcome outcome) {
        Objects.requireNonNull(Objects.requireNonNull(timers.get(stage)).get(outcome))
                .record(duration);
        log.atInfo()
                .addKeyValue("event.name", "practice_review.stage.ended")
                .addKeyValue("review.stage", stage.tag)
                .addKeyValue("review.stage.outcome", outcome.tag)
                .addKeyValue("duration.ms", duration.toMillis())
                .log("Review stage ended");
    }

    /** Starts a stage on this thread. End it on the same thread. */
    public Running start(Stage stage) {
        Span span = tracer.nextSpan().name("practice_review." + stage.tag).start();
        return new Running(stage, span, tracer.withSpan(span), System.nanoTime());
    }

    public final class Running {
        private final Stage stage;
        private final Span span;
        private final Tracer.SpanInScope scope;
        private final long startedNanos;
        private boolean ended;

        private Running(Stage stage, Span span, Tracer.SpanInScope scope, long startedNanos) {
            this.stage = stage;
            this.span = span;
            this.scope = scope;
            this.startedNanos = startedNanos;
        }

        /** Records the stage once. A second call does nothing. */
        public void end(Outcome outcome) {
            if (ended) return;
            long nanos = System.nanoTime() - startedNanos;
            Objects.requireNonNull(Objects.requireNonNull(timers.get(stage)).get(outcome))
                    .record(nanos, TimeUnit.NANOSECONDS);
            span.tag("hephaestus.review.stage.outcome", outcome.tag);
            log.atInfo()
                    .addKeyValue("event.name", "practice_review.stage.ended")
                    .addKeyValue("review.stage", stage.tag)
                    .addKeyValue("review.stage.outcome", outcome.tag)
                    .addKeyValue("duration.ms", TimeUnit.NANOSECONDS.toMillis(nanos))
                    .log("Review stage ended");
            close();
        }

        /** Ends the span without a sample: the stage did not run, for example because the sandbox had no room. */
        public void discard() {
            if (ended) return;
            close();
        }

        private void close() {
            ended = true;
            scope.close();
            span.end();
        }
    }
}
