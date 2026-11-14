package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.FeedbackRetentionProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Erases every conversation entry that reaches {@link FeedbackRetentionProperties#feedbackRetention()}, as a delete
 * erases it. It runs once a week and erases an entry up to two weeks before its limit, so a missed run still keeps it:
 * an erased entry also erases every summary over it, and a nightly run would cost each long conversation a full
 * compaction every night. A run erases at most {@link #MAX_PER_RUN} entries for each workspace and says so when it
 * stops there.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Finds the workspaces with conversation entries first, then erases in each one")
public class ConversationRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionSweeper.class);

    /** Two weekly runs: one can be missed without an entry outliving its limit. */
    static final Duration EARLY = Duration.ofDays(14);

    static final int BATCH = 500;
    static final int MAX_PER_RUN = 20_000;

    private final ConversationEntryRepository entries;
    private final ConversationEntryService entryService;
    private final FeedbackRetentionProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 45 3 * * SUN")
    @SchedulerLock(name = "conversation-retention-sweep", lockAtMostFor = "PT3H", lockAtLeastFor = "PT30S")
    public void sweep() {
        sweepNow();
    }

    /** @return how many entries it erased across every workspace */
    public long sweepNow() {
        Instant cutoff = clock.instant().minus(properties.feedbackRetention().minus(EARLY));
        long erased = 0;
        for (long workspaceId : entries.findWorkspaceIdsWithUnredactedEntries()) {
            try {
                int done;
                int inWorkspace = 0;
                do {
                    done = entryService.redactWrittenBefore(workspaceId, cutoff, BATCH);
                    inWorkspace += done;
                } while (done == BATCH && inWorkspace < MAX_PER_RUN);
                if (inWorkspace >= MAX_PER_RUN) {
                    log.warn(
                            "conversation.retention: workspaceId={} has more expired entries than one run erases",
                            workspaceId);
                }
                erased += inWorkspace;
            } catch (RuntimeException e) {
                log.warn("conversation.retention: sweep failed for workspaceId={}: {}", workspaceId, e.toString());
            }
        }
        if (erased > 0) {
            log.info("conversation.retention: erased {} conversation entries older than the retention", erased);
        }
        return erased;
    }
}
