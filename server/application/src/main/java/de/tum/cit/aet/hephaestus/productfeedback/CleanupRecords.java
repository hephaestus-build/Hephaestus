package de.tum.cit.aet.hephaestus.productfeedback;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Before erasure deletes reports, it keeps what is needed to find and scrub their issues on GitHub: the repository, the
 * installation, the marker and the issue number when known. None of it is person data.
 */
@Component
@RequiredArgsConstructor
public class CleanupRecords {
    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackCleanupRepository cleanups;
    private final Clock clock;

    void keepFor(List<ProductFeedbackFiling> erased) {
        Instant now = clock.instant();
        for (ProductFeedbackFiling filing : erased) {
            keepPromotion(filing, now);
            // Any report a write may have reached GitHub for: filed, filing now, or stopped after an attempt.
            boolean mayExist = filing.getState() == FilingState.FILED
                    || filing.getState() == FilingState.FILING
                    || (filing.getState() == FilingState.STOPPED && filing.getFirstAttemptAt() != null);
            if (!mayExist || filing.getRepositoryId() == null || filing.getInstallationId() == null) continue;
            var record = new ProductFeedbackCleanup(
                    Objects.requireNonNull(filing.getRepository()),
                    filing.getRepositoryId(),
                    filing.getInstallationId(),
                    filing.getFeedbackId(),
                    filing.getIssueNumber(),
                    filing.publicRoute(),
                    filing.getFirstAttemptAt() == null ? now.minusSeconds(3600) : filing.getFirstAttemptAt(),
                    now);
            record.setIssueNodeId(filing.getIssueNodeId());
            // An in-flight write can still land; reconcile only after its deadline has passed.
            Instant lease = filing.getLeaseUntil();
            if (lease != null && lease.isAfter(now)) record.setNextAttemptAt(lease.plusSeconds(60));
            cleanups.save(record);
        }
    }

    /** A promotion that froze its recipient may have created a public issue, so that issue needs its own record. */
    private void keepPromotion(ProductFeedbackFiling filing, Instant now) {
        String repository = filing.getPromotionRepository();
        Long repositoryId = filing.getPromotionRepositoryId();
        Long installationId = filing.getPromotionInstallationId();
        Instant requested = filing.getPublishRequestedAt();
        if (repository == null || repositoryId == null || installationId == null || requested == null) return;
        var record = new ProductFeedbackCleanup(
                repository,
                repositoryId,
                installationId,
                IssueRenderer.promotionMarker(filing.getFeedbackId()),
                null,
                true,
                requested.minusSeconds(60),
                now);
        Instant lease = filing.getLeaseUntil();
        if (lease != null && lease.isAfter(now)) record.setNextAttemptAt(lease.plusSeconds(60));
        cleanups.save(record);
    }

    /** Keeps the cleanup records of an account's reports; call it in the transaction that deletes them. */
    public void keepForAccount(long accountId) {
        keepFor(filings.lockAllByAccount(accountId));
    }

    void keepForWorkspace(long workspaceId) {
        keepFor(filings.lockAllByWorkspace(workspaceId));
    }
}
