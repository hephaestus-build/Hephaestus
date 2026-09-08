package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalDeliveryListener;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppCompositionListener;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.UnpreparedFeedbackLanes;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries unfinished feedback preparation for completed reviews within the recovery window.
 *
 * <p>Completion marks distinguish a successful empty result from preparation that never completed.
 * The preparers own idempotency; this sweep does not rerun model composition or provider delivery.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Recovering unprepared feedback lanes across all workspaces on a bounded-lookback schedule")
public class FeedbackLanePreparationSweeper {

    private static final Logger log = LoggerFactory.getLogger(FeedbackLanePreparationSweeper.class);

    /** Jobs older than this are excluded even if preparation remains incomplete. */
    static final Duration LOOKBACK = Duration.ofHours(24);

    /** Gives asynchronous listeners time to finish before recovery can select the same job. */
    static final Duration SETTLE = Duration.ofMinutes(10);

    static final int MAX_JOBS_PER_PASS = 500;

    private final AgentJobRepository agentJobRepository;
    private final ConversationalDeliveryListener inChatLane;
    private final InAppCompositionListener inAppLane;
    private final MeterRegistry meterRegistry;

    public FeedbackLanePreparationSweeper(
            AgentJobRepository agentJobRepository,
            ConversationalDeliveryListener inChatLane,
            InAppCompositionListener inAppLane,
            MeterRegistry meterRegistry) {
        this.agentJobRepository = agentJobRepository;
        this.inChatLane = inChatLane;
        this.inAppLane = inAppLane;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(cron = "0 25 * * * *")
    @SchedulerLock(name = "feedback-lane-preparation-sweep", lockAtMostFor = "PT20M", lockAtLeastFor = "PT30S")
    public void sweep() {
        sweepNow(Instant.now());
    }

    /** Selects unfinished preparation in {@code [now - LOOKBACK, now - SETTLE)}. */
    public SweepOutcome sweepNow(Instant now) {
        List<UnpreparedFeedbackLanes> pending = agentJobRepository.findUnpreparedFeedbackLanes(
                now.minus(LOOKBACK), now.minus(SETTLE), PageRequest.of(0, MAX_JOBS_PER_PASS));
        if (pending.isEmpty()) {
            return SweepOutcome.NOTHING;
        }
        int recovered = 0;
        int prepared = 0;
        int failed = 0;
        for (UnpreparedFeedbackLanes job : pending) {
            int unitsFromThisJob = 0;
            boolean anyLaneRan = false;
            boolean anyLaneFailed = false;
            if (job.inChatPending()) {
                LaneResult result = run(Lane.IN_CHAT, job);
                unitsFromThisJob += result.units();
                anyLaneRan |= result.ran();
                anyLaneFailed |= !result.ran();
            }
            if (job.inAppPending()) {
                LaneResult result = run(Lane.IN_APP, job);
                unitsFromThisJob += result.units();
                anyLaneRan |= result.ran();
                anyLaneFailed |= !result.ran();
            }
            if (anyLaneRan) {
                recovered++;
                prepared += unitsFromThisJob;
            }
            if (anyLaneFailed) {
                failed++;
            }
        }
        log.info(
                "feedback.lane.sweep: {} job(s) had an unprepared lane; recovered {}, prepared {} unit(s), {} still failing",
                pending.size(),
                recovered,
                prepared,
                failed);
        return new SweepOutcome(pending.size(), recovered, prepared, failed);
    }

    private LaneResult run(Lane lane, UnpreparedFeedbackLanes job) {
        try {
            int units =
                    switch (lane) {
                        case IN_CHAT -> inChatLane.prepare(job.agentJobId(), job.workspaceId());
                        case IN_APP -> inAppLane.prepare(job.agentJobId(), job.workspaceId());
                    };
            meterRegistry
                    .counter(AgentMetrics.FEEDBACK_LANE_SWEEP_RECOVERED, "lane", lane.tag)
                    .increment();
            return new LaneResult(true, units);
        } catch (RuntimeException e) {
            log.warn(
                    "feedback.lane.sweep: {} lane still failing for jobId={}: {}",
                    lane.tag,
                    job.agentJobId(),
                    e.toString());
            meterRegistry
                    .counter(AgentMetrics.FEEDBACK_LANE_SWEEP_FAILURE, "lane", lane.tag)
                    .increment();
            return new LaneResult(false, 0);
        }
    }

    private enum Lane {
        IN_CHAT("in-chat"),
        IN_APP("in-app");

        private final String tag;

        Lane(String tag) {
            this.tag = tag;
        }
    }

    private record LaneResult(boolean ran, int units) {}

    /**
     * @param found jobs in the window with at least one unprepared lane
     * @param recovered jobs where at least one lane ran to completion this pass
     * @param preparedUnits feedback units newly written by this pass
     * @param stillFailing jobs where at least one lane threw again
     */
    public record SweepOutcome(int found, int recovered, int preparedUnits, int stillFailing) {
        static final SweepOutcome NOTHING = new SweepOutcome(0, 0, 0, 0);
    }
}
