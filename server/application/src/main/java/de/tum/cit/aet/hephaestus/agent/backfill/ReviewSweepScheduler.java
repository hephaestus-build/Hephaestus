package de.tum.cit.aet.hephaestus.agent.backfill;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opens a campaign for every sweep schedule that has come due.
 *
 * <p>Every other way into the review path waits for an event, a reconciliation, a person asking, or an
 * admin scoping a campaign — so an artifact nothing ever announced would otherwise have no ledger row at
 * all, making "we never looked" indistinguishable from "we looked and declined". A workspace with a
 * schedule gets a row for every artifact in the window either way.
 *
 * <p>Deliberately thin: it selects due rows, hands each to {@link ReviewSweepCampaignOpener}, and logs.
 * The pacing, budget checks, cursor and per-artifact isolation belong to {@code ReviewBackfillDriver}.
 * Locked like {@code PendingSignalReaper} — two replicas opening the same due schedule would both
 * enumerate and price a scope before the campaign-under-way check settled it.
 */
@ConditionalOnServerRole
@Component
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Due schedules are opened for every workspace on the instance")
public class ReviewSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReviewSweepScheduler.class);

    /** Each schedule becomes a campaign the driver paces; pulling in more would just queue on a saturated driver. */
    private static final int MAX_SCHEDULES_PER_TICK = 20;

    private final ReviewSweepScheduleRepository scheduleRepository;
    private final ReviewSweepCampaignOpener opener;

    public ReviewSweepScheduler(ReviewSweepScheduleRepository scheduleRepository, ReviewSweepCampaignOpener opener) {
        this.scheduleRepository = scheduleRepository;
        this.opener = opener;
    }

    /** The cadence a schedule keeps is its own {@code nextRunAt}; this only has to notice one has passed. */
    @Scheduled(fixedDelay = 5, initialDelay = 3, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "review-sweep-scheduler", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void tick() {
        Instant now = Instant.now();
        List<ReviewSweepSchedule> due = scheduleRepository.findDue(now, PageRequest.ofSize(MAX_SCHEDULES_PER_TICK));
        for (ReviewSweepSchedule schedule : due) {
            try {
                ReviewSweepOutcome outcome = opener.openDueRun(schedule.getId(), now);
                if (outcome != ReviewSweepOutcome.OPENED) {
                    log.debug("Review sweep skipped: scheduleId={}, outcome={}", schedule.getId(), outcome);
                }
            } catch (RuntimeException e) {
                // The failed transaction rolled its own advance back, so without deferAfterFailure the
                // schedule stays due and would retry itself forever.
                log.warn(
                        "Review sweep failed to open, deferring to the next occurrence: scheduleId={}",
                        schedule.getId(),
                        e);
                try {
                    opener.deferAfterFailure(schedule.getId(), now);
                } catch (RuntimeException deferFailure) {
                    log.warn("Review sweep could not be deferred: scheduleId={}", schedule.getId(), deferFailure);
                }
            }
        }
    }
}
