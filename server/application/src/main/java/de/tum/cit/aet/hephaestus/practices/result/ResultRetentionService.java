package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.FeedbackRetentionProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Erases what Hephaestus says about a person once {@link FeedbackRetentionProperties#feedbackRetention()} has passed,
 * the window that conversations use too: feedback counted from its delivery or, when it was never delivered, its
 * creation, then the raw proof of the scans that ended before, then every result that no remaining feedback cites and
 * no running review reads, counted from when it was recorded. A result therefore stays as long as the latest delivery
 * that cites it.
 */
@Slf4j
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ResultRetentionService {
    static final int FEEDBACK_BATCH = 500;
    static final int RESULT_BATCH = 500;

    private final ReviewRepository reviews;
    private final ResultRepository results;
    private final FeedbackRepository feedback;
    private final FeedbackCopyCleanupCustody custody;
    private final ScanReportService reports;
    private final FeedbackRetentionProperties retention;
    private final PlatformTransactionManager transactions;
    private final Clock clock;

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "result-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @WorkspaceAgnostic("Enumerates workspaces before each workspace's scoped retention transactions")
    public void purge() {
        var cutoff = clock.instant().minus(retention.feedbackRetention());
        var workspaces = new TreeSet<>(feedback.workspacesWithExpiredFeedback(cutoff));
        workspaces.addAll(reviews.workspacesWithExpiredProof(cutoff));
        for (var workspace : workspaces) {
            try {
                purgeWorkspace(workspace, cutoff);
            } catch (RuntimeException e) {
                // One workspace's failure leaves the others' erasure due; the next run retries it.
                log.warn("result.retention: purge failed for workspaceId={}: {}", workspace, e.toString());
            }
        }
    }

    public void purgeWorkspace(long workspaceId, Instant cutoff) {
        var transaction = new TransactionTemplate(transactions);
        // Feedback first: a result stays while any feedback that cites it is kept.
        List<UUID> expired;
        do {
            expired = transaction.execute(status -> {
                var ids = feedback.expiredIds(workspaceId, cutoff, FEEDBACK_BATCH);
                if (ids.isEmpty()) return ids;
                custody.requireAcknowledged(workspaceId, ids);
                feedback.deleteByWorkspaceIdAndIds(workspaceId, ids.toArray(UUID[]::new));
                return ids;
            });
        } while (expired != null && expired.size() == FEEDBACK_BATCH);
        transaction.executeWithoutResult(status -> {
            for (var scan : reviews.expiredScanIds(workspaceId, cutoff)) reports.expire(workspaceId, scan);
        });
        // Then results, in batches: the first run after an upgrade can find years of them.
        Integer erased;
        do {
            erased = transaction.execute(status -> {
                var ids = results.expiredUnreferencedIds(workspaceId, cutoff, RESULT_BATCH);
                if (ids.isEmpty()) return 0;
                var parents = results.findByWorkspaceIdAndIdIn(workspaceId, ids).stream()
                        .map(r -> r.getReviewId())
                        .distinct()
                        .sorted(Comparator.comparing(UUID::toString))
                        .toList();
                for (var parent : parents)
                    if (reviews.lockByIdAndWorkspaceId(parent, workspaceId).isEmpty())
                        throw new IllegalStateException("The retained review is missing.");
                for (var id : ids)
                    if (results.lockByIdAndWorkspaceId(id, workspaceId).isEmpty())
                        throw new IllegalStateException("The retained result is missing.");
                reports.eraseResultReferences(workspaceId, ids);
                return results.deleteWithoutFeedbackBasis(workspaceId, ids.toArray(UUID[]::new));
            });
        } while (erased != null && erased == RESULT_BATCH);
    }
}
