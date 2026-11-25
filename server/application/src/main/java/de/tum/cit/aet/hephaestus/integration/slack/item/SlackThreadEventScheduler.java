package de.tum.cit.aet.hephaestus.integration.slack.item;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationClockStep;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.SweepRowFailures;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadEvents;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.thread.SlackThreadCandidateSource;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompleted;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty(value = "hephaestus.integration.slack.conversation-ingest.enabled", matchIfMissing = true)
@ConditionalOnBooleanProperty("hephaestus.integration.slack.enabled")
public class SlackThreadEventScheduler {
    private static final Logger log = LoggerFactory.getLogger(SlackThreadEventScheduler.class);
    private static final int MIN_TURNS = 4;
    private static final int MIN_GROWTH = 2;
    private static final Duration QUIESCENCE = Duration.ofMinutes(10);
    private final SlackThreadCandidateSource candidates;
    private final ThreadItemSource source;
    private final ItemIndexService items;
    private final SlackThreadRepository threads;
    private final ReviewService reviews;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SlackThreadEventScheduler(
            SlackThreadCandidateSource candidates,
            ThreadItemSource source,
            ItemIndexService items,
            SlackThreadRepository threads,
            ReviewService reviews,
            TransactionTemplate transactions,
            Clock clock) {
        this.candidates = candidates;
        this.source = source;
        this.items = items;
        this.threads = threads;
        this.reviews = reviews;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Bean
    @ConditionalOnEvaluation
    EvaluationClockStep slackThreadClockStep(ObjectProvider<SlackThreadEventScheduler> schedule) {
        return instant -> schedule.getObject().sweepNow();
    }

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "conversation-thread-review", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @WorkspaceAgnostic("Discovers Slack thread candidates globally and records each event with its workspace")
    public void sweep() {
        sweepNow();
    }

    @WorkspaceAgnostic("Cross-workspace Slack source discovery writes an explicit workspace on every item and event")
    public long sweepNow() {
        Instant now = clock.instant();
        long offered = 0;
        for (var candidate : candidates.settledCandidates(MIN_TURNS)) {
            try {
                Long last = SlackTs.toEpochMicros(candidate.lastTs());
                if (last == null || Instant.EPOCH.plus(last, ChronoUnit.MICROS).isAfter(now.minus(QUIESCENCE)))
                    continue;
                if (candidates.liveTurnCount(candidate.workspaceId(), candidate.channelId(), candidate.threadTs())
                                < MIN_TURNS
                        || candidates.liveTurnCountSince(
                                        candidate.workspaceId(),
                                        candidate.channelId(),
                                        candidate.threadTs(),
                                        candidate.lastReviewedTs())
                                < MIN_GROWTH) continue;
                Boolean recorded = transactions.execute(status -> {
                    var item = items.upsert(new ItemRegistration(
                            candidate.workspaceId(), ThreadEvents.THREAD, candidate.threadId(), null, null));
                    ItemSnapshot pinned;
                    try {
                        pinned = source.pin(item, null);
                    } catch (ItemCaptureException unavailable) {
                        // A removed, withheld or unreadable thread offers no review; its code is the source's.
                        return false;
                    }
                    items.record(new ItemEventInput(
                            candidate.workspaceId(),
                            item.itemId(),
                            ThreadEvents.SETTLED,
                            pinned.version().value(),
                            now,
                            FoundBy.SYNC,
                            null,
                            null));
                    return true;
                });
                if (Boolean.TRUE.equals(recorded)) offered++;
            } catch (RuntimeException failure) {
                SweepRowFailures.skipUnlessTransient(
                        log, "conversation-thread-review", candidate.workspaceId(), candidate.threadId(), failure);
            }
        }
        return offered;
    }

    @ApplicationModuleListener
    public void onReviewCompleted(ReviewCompleted event) {
        reviews.find(event.workspaceId(), event.reviewId()).ifPresent(review -> {
            var reviewed = review.version();
            if (!ThreadEvents.THREAD.equals(review.item().kind()) || reviewed == null) return;
            Long sourceId = review.item().sourceId();
            if (sourceId == null) return;
            threads.findByWorkspaceIdAndId(event.workspaceId(), sourceId).ifPresent(thread -> {
                String reviewedThrough = thread.getLastTs();
                if (reviewedThrough == null) return;
                if (readsAt(review.item(), reviewed))
                    candidates.markReviewed(event.workspaceId(), sourceId, reviewedThrough);
            });
        });
    }

    /** Whether the thread reads at {@code version} now. One that cannot be read stays unreviewed for a later offer. */
    private boolean readsAt(ItemRef item, ItemVersion version) {
        try {
            return source.pin(item, null).version().equals(version);
        } catch (ItemCaptureException unavailable) {
            return false;
        }
    }
}
