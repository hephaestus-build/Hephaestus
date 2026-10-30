package de.tum.cit.aet.hephaestus.agent.metrics;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.Serial;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Runtime phase durations with bounded tags and no effect on the operation when a metrics handler fails. */
@Component
@ConditionalOnWorkerRole
public class AgentPhaseObservations {
    private static final Logger log = LoggerFactory.getLogger(AgentPhaseObservations.class);

    public enum Workload {
        CONVERSATION,
        REVIEW
    }

    public enum Phase {
        ADMISSION_WAIT,
        SANDBOX_ACQUIRE,
        SANDBOX_START,
        HOST_START,
        MODEL_FIRST_TOKEN,
        MODEL_CALL,
        TOOL_CALL
    }

    public enum Outcome {
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum Acquisition {
        NONE,
        ASSIGNED,
        WARM,
        COLD
    }

    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    public AgentPhaseObservations(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.meters = meters;
    }

    public Sample start(Workload workload, Phase phase) {
        Observation observation;
        try {
            observation = Observation.createNotStarted(AgentMetrics.AGENT_PHASE_DURATION, observations)
                    .lowCardinalityKeyValue("workload", tag(workload))
                    .lowCardinalityKeyValue("phase", tag(phase))
                    .lowCardinalityKeyValue("outcome", "active")
                    .lowCardinalityKeyValue("acquisition", tag(Acquisition.NONE))
                    .start();
        } catch (RuntimeException exception) {
            failedMetric(exception);
            observation = Observation.NOOP;
        }
        return new Sample(observation);
    }

    /** The enclosing operation marks completion only after its existing success boundary. */
    public static final class Sample implements AutoCloseable {
        private final Observation observation;
        private Outcome outcome = Outcome.FAILED;
        private Acquisition acquisition = Acquisition.NONE;

        private Sample(Observation observation) {
            this.observation = observation;
        }

        public void completed() {
            outcome = Outcome.COMPLETED;
        }

        public void cancelled() {
            outcome = Outcome.CANCELLED;
        }

        public void acquisition(Acquisition value) {
            acquisition = value;
        }

        @Override
        public void close() {
            try {
                observation
                        .lowCardinalityKeyValue("outcome", tag(outcome))
                        .lowCardinalityKeyValue("acquisition", tag(acquisition));
                if (outcome == Outcome.FAILED) observation.error(new PhaseFailure());
                observation.stop();
            } catch (RuntimeException exception) {
                failedMetric(exception);
            }
        }
    }

    public void record(Workload workload, Phase phase, Duration duration) {
        record(meters, workload, phase, duration, Outcome.COMPLETED);
    }

    public static void record(MeterRegistry meters, Workload workload, Phase phase, Duration duration) {
        record(meters, workload, phase, duration, Outcome.COMPLETED);
    }

    /** Queue and remote tool spans have already ended when their timestamps reach this process. */
    public static void record(
            MeterRegistry meters, Workload workload, Phase phase, Duration duration, Outcome outcome) {
        if (duration.isNegative()) return;
        try {
            Timer.builder(AgentMetrics.AGENT_PHASE_REPORTED_DURATION)
                    .tags(
                            "workload",
                            tag(workload),
                            "phase",
                            tag(phase),
                            "outcome",
                            tag(outcome),
                            "acquisition",
                            tag(Acquisition.NONE),
                            "error",
                            outcome == Outcome.FAILED ? PhaseFailure.class.getSimpleName() : "none")
                    .register(meters)
                    .record(duration);
        } catch (RuntimeException exception) {
            failedMetric(exception);
        }
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static void failedMetric(RuntimeException exception) {
        log.warn(
                "Could not record runtime phase metrics: {}",
                exception.getClass().getSimpleName());
    }

    /** A fixed exception type keeps the metric error tag bounded and private messages out of traces. */
    private static final class PhaseFailure extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private PhaseFailure() {
            super("Runtime phase did not complete", null, false, false);
        }
    }
}
