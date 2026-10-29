package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Samples how many conversation turns wait for a worker, on a timer and never on a scrape. Gated to the server role, so a
 * worker or webhook pod does not publish a permanent 0 into the same series.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Fleet-wide queue depth; not workspace-scoped")
public class MentorTurnQueueSampler {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnQueueSampler.class);

    private final MentorTurnRepository turns;
    private final AtomicLong queued = new AtomicLong();

    public MentorTurnQueueSampler(MentorTurnRepository turns, MeterRegistry meters) {
        this.turns = turns;
        Gauge.builder(AgentMetrics.MENTOR_TURN_QUEUED, queued, AtomicLong::get)
                .description("conversation turns that wait for a worker; last-good when a sample fails")
                .register(meters);
    }

    @Scheduled(fixedDelay = 30, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void sample() {
        try {
            queued.set(turns.countByStatus(MentorTurn.Status.QUEUED));
        } catch (RuntimeException e) {
            // A database blip must not read as an empty queue.
            log.warn(
                    "conversation turn queue sample failed, keeping the last value: {}",
                    e.getClass().getSimpleName());
        }
    }
}
