package de.tum.cit.aet.hephaestus.integration.submission.adapter;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Job;
import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics.Outcome;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.submission.TeamItemVersionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly sweep that ends each submitted version whose expiry date passed; the database then deletes its content
 * (ADR 0165). Each batch commits on its own, so a failure keeps the batches before it. A run calls again while a batch
 * is full, for at most {@link #BUDGET}; what it leaves, and the versions it skipped, the next run ends. A run that
 * leaves a due version is incomplete.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Expiry ends the due versions of every workspace")
class TeamVersionExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(TeamVersionExpirySweeper.class);

    /** The versions that one batch ends at most. Small, because each takes all its content with it in one transaction. */
    static final int BATCH = 20;

    /** A run starts no batch after this, so it normally ends inside {@code lockAtMostFor}. */
    static final Duration BUDGET = Duration.ofMinutes(4);

    private final TeamItemVersionRepository versions;
    private final PrivacyJobMetrics metrics;
    private final Clock clock;

    TeamVersionExpirySweeper(TeamItemVersionRepository versions, PrivacyJobMetrics metrics, Clock clock) {
        this.versions = versions;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 * * * *")
    @SchedulerLock(name = "team-version-expiry-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void sweep() {
        sweepNow();
    }

    /** One run without the scheduler lock, for a test that drives it. */
    public void sweepNow() {
        Instant deadline = clock.instant().plus(BUDGET);
        int expired = 0;
        try {
            int batch;
            do {
                batch = versions.expireDue(BATCH);
                expired += batch;
            } while (batch == BATCH && clock.instant().isBefore(deadline));
            // Asked, not inferred from the batches: a held version is skipped, and a full last batch may drain all.
            boolean remains = versions.existsDue();
            metrics.record(Job.TEAM_VERSION_EXPIRY, remains ? Outcome.INCOMPLETE : Outcome.SUCCESS);
            metrics.recordAffected(Job.TEAM_VERSION_EXPIRY, expired);
            if (remains) {
                log.warn("team.expiry: a version past its expiry date remains after the run");
            }
            if (expired > 0) {
                log.info("team.expiry: ended {} version(s) past their expiry date", expired);
            }
        } catch (RuntimeException e) {
            metrics.recordAffected(Job.TEAM_VERSION_EXPIRY, expired);
            metrics.record(Job.TEAM_VERSION_EXPIRY, Outcome.FAILURE);
            throw e;
        }
    }
}
