package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import java.time.Clock;
import java.time.Duration;
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
    /** A write that an action may still make reaches GitHub within its lease. */
    private static final Duration AFTER_A_WRITE = ActionExecutor.LEASE.plusSeconds(60);

    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackCleanupRepository cleanups;
    private final FileIssueQueue queue;
    private final Clock clock;

    void keepFor(List<ProductFeedbackFiling> erased) {
        Instant now = clock.instant();
        for (ProductFeedbackFiling filing : erased) {
            keepPromotion(filing, now);
            keepPredecessor(filing, now);
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
            // The report's file_issue action may still be writing; reconcile only after its lease has passed.
            keep(record, filing.getState() == FilingState.FILING ? AFTER_A_WRITE : Duration.ZERO);
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
        // The /publish action may still be writing; reconcile only after its lease has passed.
        keep(record, AFTER_A_WRITE);
    }

    /** The private issue that a promotion closed still holds the report's text. */
    private void keepPredecessor(ProductFeedbackFiling filing, Instant now) {
        String repository = filing.getPredecessorRepository();
        Long repositoryId = filing.getPredecessorRepositoryId();
        Long installationId = filing.getPredecessorInstallationId();
        Long number = filing.getPredecessorIssueNumber();
        Instant first = filing.getFirstAttemptAt();
        if (repository == null || repositoryId == null || installationId == null || number == null) return;
        var record = new ProductFeedbackCleanup(
                repository,
                repositoryId,
                installationId,
                filing.getFeedbackId(),
                number,
                false,
                first == null ? now.minusSeconds(3600) : first,
                now);
        record.setIssueNodeId(filing.getPredecessorIssueNodeId());
        keep(record, Duration.ZERO);
    }

    /** Keeps a record and queues its scrub, which a maintainer takes over after {@link CleanupService#MANUAL_AFTER}. */
    private void keep(ProductFeedbackCleanup record, Duration delay) {
        cleanups.save(record);
        queue.cleanup(record.getId(), delay, record.getCreatedAt().plus(CleanupService.MANUAL_AFTER));
    }

    /** Keeps the cleanup records of an account's reports; call it in the transaction that deletes them. */
    public void keepForAccount(long accountId) {
        keepFor(filings.lockAllByAccount(accountId));
    }

    void keepForWorkspace(long workspaceId) {
        keepFor(filings.lockAllByWorkspace(workspaceId));
    }
}
