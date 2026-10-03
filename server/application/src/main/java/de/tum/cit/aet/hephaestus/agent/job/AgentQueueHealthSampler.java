package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Queue-health gauges for the {@code agent_job} queue, sampled on a timer and never on a request path.
 * All of them come from ONE query ({@link AgentJobRepository#queueHealthSnapshot}): separate COUNT/MIN
 * scans would cost the most exactly during the incident that makes the signal matter.
 *
 * <p>Must stay gated to the server role, not just to {@code @Scheduled}: a registered-but-never-sampled
 * gauge on a worker or webhook pod publishes a permanent 0 into the same {@code agent.queue.*} series
 * the server publishes real values into, dragging every cross-replica aggregate low.
 */
@ConditionalOnServerRole
@Component
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Fleet-wide queue health; not workspace-scoped")
public class AgentQueueHealthSampler {

    private static final Logger log = LoggerFactory.getLogger(AgentQueueHealthSampler.class);

    private final AgentJobRepository jobRepository;
    private final AtomicLong depth = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong held = new AtomicLong();
    private final AtomicLong running = new AtomicLong();
    private final Counter samplerFailures;

    public AgentQueueHealthSampler(AgentJobRepository jobRepository, MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        Gauge.builder(AgentMetrics.AGENT_QUEUE_DEPTH, depth, AtomicLong::get)
                .description(
                        "QUEUED jobs currently eligible to run (available_at <= now); last-good on sampler failure")
                .register(meterRegistry);
        Gauge.builder(AgentMetrics.AGENT_QUEUE_OLDEST_AGE_SECONDS, oldestAgeSeconds, AtomicLong::get)
                .description(
                        "Age in seconds of the oldest eligible QUEUED job; 0 when the queue is empty; last-good on sampler failure")
                .register(meterRegistry);
        // Without this, a workspace over its monthly LLM cap is indistinguishable from an idle instance:
        // its whole backlog sits at available_at in the future, so agent.queue.depth reads 0 and the
        // "queue is backed up" alert stays silent while nothing runs. Alert on held > 0 sustained.
        Gauge.builder(AgentMetrics.AGENT_QUEUE_HELD, held, AtomicLong::get)
                .description(
                        "QUEUED jobs parked on an admin-undoable hold (monthly LLM cap); excluded from agent.queue.depth; last-good on sampler failure")
                .register(meterRegistry);
        Gauge.builder(AgentMetrics.AGENT_QUEUE_RUNNING, running, AtomicLong::get)
                .description("Jobs currently RUNNING fleet-wide; last-good on sampler failure")
                .register(meterRegistry);
        this.samplerFailures = Counter.builder(AgentMetrics.AGENT_QUEUE_HEALTH_SAMPLER_FAILURES)
                .description("Queue-health sample passes that failed (gauges kept their last-good value)")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 30, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void sample() {
        Instant now = Instant.now();
        try {
            AgentJobRepository.QueueHealthSnapshot snapshot = jobRepository.queueHealthSnapshot(now);
            depth.set(snapshot.getDepth());
            held.set(snapshot.getHeld());
            running.set(snapshot.getRunning());
            Instant oldest = snapshot.getOldestAvailableAt();
            oldestAgeSeconds.set(
                    oldest != null ? Math.max(0, Duration.between(oldest, now).getSeconds()) : 0L);
        } catch (Exception e) {
            // Keep the last-good gauge values: a DB blip must not read as "queue is empty".
            samplerFailures.increment();
            log.warn("Queue-health sample failed, keeping last-good values: {}", e.getMessage());
        }
    }
}
