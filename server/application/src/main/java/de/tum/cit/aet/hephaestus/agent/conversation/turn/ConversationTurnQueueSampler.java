package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Samples how many conversation turns wait to start, on a timer and never on a scrape. Gated to the server role, so
 * a webhook pod does not publish a permanent 0 into the same series.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Fleet-wide queue depth; not workspace-scoped")
public class ConversationTurnQueueSampler {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnQueueSampler.class);

    private final ConversationTurnRepository turns;
    private final AtomicLong queued = new AtomicLong();

    public ConversationTurnQueueSampler(ConversationTurnRepository turns, MeterRegistry meters) {
        this.turns = turns;
        Gauge.builder(AgentMetrics.CONVERSATION_TURN_QUEUED, queued, AtomicLong::get)
                .description("conversation turns that wait to start; last-good when a sample fails")
                .register(meters);
    }

    @Scheduled(fixedDelay = 30, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void sample() {
        try {
            queued.set(
                    turns.countByStatusAndSurfaceNot(ConversationTurn.Status.QUEUED, ConversationTurn.Surface.SYSTEM));
        } catch (RuntimeException e) {
            // A database blip must not read as an empty queue.
            log.warn(
                    "conversation turn queue sample failed, keeping the last value: {}",
                    e.getClass().getSimpleName());
        }
    }
}
