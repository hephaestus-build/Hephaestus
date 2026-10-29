package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnWaits;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes back the turns whose lease the worker stopped renewing: a crashed worker or host. Each turn is taken back and
 * billed in its own transaction, so one failure cannot stop the turns behind it. Also ends queued questions that
 * exceed their persisted start budget, without claiming them.
 */
@ConditionalOnServerRole
@Component
@RequiredArgsConstructor
public class MentorTurnLeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnLeaseReaper.class);

    private final MentorTurnService turns;
    private final MentorTurnCompletion completion;
    private final MeterRegistry meters;
    private final PlatformTransactionManager transactions;
    private final MentorTurnWaits waits;

    @Scheduled(fixedDelayString = "${hephaestus.mentor.turn.lease-reaper-delay:PT15S}")
    @SchedulerLock(name = "mentor-turn-lease-reaper", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    @WorkspaceAgnostic("Sweeps expired leases and queued start waits across tenants; each turn carries its workspace")
    public void reap() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        for (UUID turnId : turns.expiredLeases()) {
            try {
                tx.executeWithoutResult(status -> turns.expireLease(turnId).ifPresent(lost -> {
                    completion.billLostAttempt(lost);
                    log.info(
                            "Mentor turn lease expired: turnId={}, attempt={}, now={}",
                            turnId,
                            lost.attempt(),
                            lost.turn().getStatus());
                }));
            } catch (RuntimeException e) {
                log.warn("Mentor turn lease reaper could not take back turn {}; trying again next tick", turnId, e);
                meters.counter(AgentMetrics.MENTOR_TURN_LEASE_REAPER_FAILURE).increment();
            }
        }
        Instant now = turns.databaseNow();
        for (UUID turnId : waits.overdueQueued(now)) {
            try {
                boolean ended = Boolean.TRUE.equals(
                        tx.execute(status -> waits.expireQueued(turnId, now, MentorTurnCompletion.COULD_NOT_START)));
                if (ended) {
                    meters.counter(AgentMetrics.MENTOR_TURN_COMPLETED, "outcome", "failed")
                            .increment();
                    log.warn("Conversation turn {} could not start before its queue deadline", turnId);
                }
            } catch (RuntimeException e) {
                log.warn("Mentor turn reaper could not end queued turn {}; trying again next tick", turnId, e);
                meters.counter(AgentMetrics.MENTOR_TURN_LEASE_REAPER_FAILURE).increment();
            }
        }
    }
}
