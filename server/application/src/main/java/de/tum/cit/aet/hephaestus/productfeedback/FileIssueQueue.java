package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Hands a report to the outbox, in the caller's transaction: its draft, its filing and what follows them. */
@Component
@RequiredArgsConstructor
class FileIssueQueue {
    static final int ACCOUNT_DAILY_DRAFTS = 5;

    private final AgentActions actions;
    private final ObjectMapper mapper;
    private final DestinationSettings destinations;
    private final ProductFeedbackFilingRepository filings;

    /**
     * Queues the draft of a report that just entered {@link FilingState#DRAFT_QUEUED}, as one {@code draft_report}
     * action. A report whose author was not told about the model, or that no model or daily limit lets draft, goes to
     * routing without a draft instead.
     */
    void draft(ProductFeedbackFiling filing, ProductFeedback report, Instant now) {
        ProductFeedbackDestination destination = destinations.current();
        // A report whose author was not told about the model never reaches one, whatever path led here.
        if (!filing.isFileDisclosed()
                || destination.getLlmModelId() == null
                || filing.getPromptInput() == null
                || overBudget(report, destination, now)) {
            DraftingService.answered(filing, null, now);
            return;
        }
        filing.setAttempt(filing.getAttempt() + 1);
        filing.setUpdatedAt(now);
        actions.enqueue(DraftingService.request(
                mapper, filing.getFeedbackId(), filing.getAttempt(), report.getAccountId(), now));
    }

    private boolean overBudget(ProductFeedback report, ProductFeedbackDestination destination, Instant now) {
        Instant day = now.truncatedTo(ChronoUnit.DAYS);
        return filings.countDraftedSince(day) >= destination.getDailyDraftLimit()
                || filings.countDraftedByAccountSince(Objects.requireNonNull(report.getAccountId()), day)
                        >= ACCOUNT_DAILY_DRAFTS;
    }

    void enqueue(FileIssueAdapter.Payload payload, @Nullable Long accountId) {
        actions.enqueue(FileIssueAdapter.request(mapper, payload, accountId));
    }

    /** Queues the scrub of one erased report's issue, due after {@code delay}; it gives up at {@code until}. */
    void cleanup(UUID recordId, Duration delay, Instant until) {
        actions.enqueueNew(CleanupIssueAdapter.request(mapper, recordId, until), delay);
    }

    /** Queues a {@code /publish} command, in the report's lane, after its filing. */
    void publish(UUID feedbackId, Instant requestedAt, @Nullable Long accountId) {
        actions.enqueue(PublishReportAdapter.request(mapper, feedbackId, requestedAt, accountId));
    }
}
