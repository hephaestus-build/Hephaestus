package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackDelivery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes the Slack replies and weekly digest notes that no server settled
 * ({@code docs/contributor/unified-pi-runtime.mdx} § Slack replies). A run stops taking posts after
 * {@code RUN_BUDGET}, so it ends well inside its lock even when Slack throttles the history reads.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackMentorReconciler {

    private static final Logger log = LoggerFactory.getLogger(SlackMentorReconciler.class);

    /** The projecting server settles a reply seconds after the turn ends; after this it is gone. */
    static final Duration GRACE = Duration.ofMinutes(2);

    /** Older replies are not worth posting any more. */
    static final Duration HORIZON = Duration.ofDays(1);

    /** With {@link #GRACE} doubled each time, the last try comes {@code GRACE * 2^(MAX_ATTEMPTS - 1)} after the end. */
    static final int MAX_ATTEMPTS = 8;

    private static final int PER_WORKSPACE = 5;

    private static final int BATCH = 50;

    /** One history read may wait out Slack's rate limit for its whole retry budget; this leaves room under the lock. */
    private static final Duration RUN_BUDGET = Duration.ofMinutes(5);

    private final MentorSlackDelivery delivery;
    private final SlackMentorProjector projector;
    private final SlackWeeklyDigestPointer weeklyDigests;
    private final Clock clock;

    public SlackMentorReconciler(
            MentorSlackDelivery delivery,
            SlackMentorProjector projector,
            SlackWeeklyDigestPointer weeklyDigests,
            Clock clock) {
        this.delivery = delivery;
        this.projector = projector;
        this.weeklyDigests = weeklyDigests;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @SchedulerLock(name = "slack-heph-reconciler", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    @WorkspaceAgnostic("Sweeps unsettled Slack replies of every workspace; each post carries its workspace")
    public void reconcile() {
        Instant now = clock.instant();
        Instant stop = now.plus(RUN_BUDGET);
        for (MentorSlackDelivery.Unsettled unsettled :
                delivery.due(now.minus(HORIZON), now, GRACE, MAX_ATTEMPTS, PER_WORKSPACE, BATCH)) {
            if (clock.instant().isAfter(stop)) {
                return;
            }
            if (!delivery.retry(unsettled.workspaceId(), unsettled.entryId())) {
                continue;
            }
            UUID turnId = unsettled.turnId();
            try {
                if (turnId != null) {
                    projector.reconcile(unsettled.workspaceId(), turnId);
                } else {
                    weeklyDigests.reconcile(unsettled.workspaceId(), unsettled.entryId());
                }
            } catch (RuntimeException e) {
                // Never rethrown: one post that cannot be settled must not hold up the others.
                log.warn("Slack post of Heph entry {} stays unsettled; trying again", unsettled.entryId(), e);
            }
        }
    }
}
