package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ResultRetentionService {
    private final ReviewRepository reviews;
    private final ResultRepository results;
    private final ScanReportService reports;
    private final PlatformTransactionManager transactions;
    private final Clock clock;

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "result-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @WorkspaceAgnostic("Enumerates workspaces before each workspace's scoped proof-retention transaction")
    public void purge() {
        var cutoff = clock.instant().minus(Duration.ofDays(400));
        var workspaces = reviews.workspacesWithExpiredProof(cutoff);
        for (var workspace : workspaces) purgeWorkspace(workspace, cutoff);
    }

    public void purgeWorkspace(long workspaceId, Instant cutoff) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var scans = reviews.expiredScanIds(workspaceId, cutoff);
            for (var scan : scans) reports.expire(workspaceId, scan);
            var ids = results.expiredUnreferencedIds(workspaceId, cutoff);
            if (ids.isEmpty()) return;
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
            results.deleteWithoutFeedbackBasis(workspaceId, ids.toArray(UUID[]::new));
        });
    }
}
