package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafting;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The worker's side of drafting. It claims a report, hands over the text the server already redacted, and stores the
 * model's raw answer. It loads in every role and decides nothing: the server's {@link RoutingService} validates the
 * answer, redacts it again and routes it.
 */
@Service
class DraftingService implements ProductFeedbackDrafting {
    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration HOLD = Duration.ofMinutes(5);
    static final int ACCOUNT_DAILY_DRAFTS = 5;

    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackRepository feedback;
    private final DestinationSettings destinations;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final ProductFeedbackMeters meters;

    DraftingService(
            ProductFeedbackFilingRepository filings,
            ProductFeedbackRepository feedback,
            DestinationSettings destinations,
            Clock clock,
            TransactionTemplate transactions,
            ProductFeedbackMeters meters) {
        this.filings = filings;
        this.feedback = feedback;
        this.destinations = destinations;
        this.clock = clock;
        this.transactions = transactions;
        this.meters = meters;
    }

    @Override
    public Optional<DraftWork> claim(String workerId) {
        while (true) {
            var outcome = transactions.execute(status -> claimOne(workerId));
            if (outcome == null || outcome.done()) return outcome == null ? Optional.empty() : outcome.work();
        }
    }

    /** {@code done} is false when a report was handed to routing without a draft, so the caller looks for the next. */
    private record Claim(boolean done, Optional<DraftWork> work) {}

    private Claim claimOne(String workerId) {
        Instant now = clock.instant();
        UUID id = filings.claimableDraft(now).orElse(null);
        if (id == null) return new Claim(true, Optional.empty());
        ProductFeedbackFiling filing = filings.findById(id).orElseThrow();
        ProductFeedback report = feedback.findById(id).orElseThrow();
        ProductFeedbackDestination destination = destinations.current();
        Long model = destination.getLlmModelId();
        String input = filing.getPromptInput();
        if (model == null || input == null || overBudget(report, destination, now)) {
            answered(filing, null, now);
            return new Claim(false, Optional.empty());
        }
        filing.setAttempt(filing.getAttempt() + 1);
        filing.setState(FilingState.DRAFTING);
        filing.setClaimedBy(workerId);
        filing.setLeaseUntil(now.plus(LEASE));
        filing.setUpdatedAt(now);
        String user = DraftPrompt.user(
                report.getKind(),
                input,
                filing.getPageRoute(),
                report.getAppVersion(),
                Snapshot.read(destination.getSnapshot()));
        return new Claim(
                true,
                Optional.of(new DraftWork(
                        id,
                        filing.getAttempt(),
                        model,
                        DraftPrompt.SYSTEM,
                        user,
                        DraftPrompt.MAX_OUTPUT_TOKENS,
                        now.plus(LEASE))));
    }

    @Override
    public boolean held(UUID feedbackId, int attempt) {
        return filings.findById(feedbackId)
                .filter(filing -> filing.getState() == FilingState.DRAFTING && filing.getAttempt() == attempt)
                .filter(filing -> {
                    Instant lease = filing.getLeaseUntil();
                    return lease != null && lease.isAfter(clock.instant());
                })
                .isPresent();
    }

    @Override
    public void complete(UUID feedbackId, int attempt, String answer, long inputTokens, long outputTokens) {
        transactions.executeWithoutResult(status -> {
            ProductFeedbackFiling filing = current(feedbackId, attempt);
            if (filing == null) return;
            count(filing, inputTokens, outputTokens);
            filing.setDraftedAt(clock.instant());
            meters.draft("answered");
            answered(filing, answer, clock.instant());
        });
    }

    @Override
    public void fail(UUID feedbackId, int attempt, Failure failure, long inputTokens, long outputTokens) {
        transactions.executeWithoutResult(status -> {
            ProductFeedbackFiling filing = current(feedbackId, attempt);
            if (filing == null) return;
            count(filing, inputTokens, outputTokens);
            meters.draft(failure.name().toLowerCase(Locale.ROOT));
            answered(filing, null, clock.instant());
        });
    }

    private static void answered(ProductFeedbackFiling filing, @Nullable String answer, Instant now) {
        filing.setAnswer(answer);
        filing.setState(FilingState.ANSWERED);
        filing.setClaimedBy(null);
        filing.setLeaseUntil(null);
        filing.setNextAttemptAt(now);
        filing.setUpdatedAt(now);
    }

    private @Nullable ProductFeedbackFiling current(UUID feedbackId, int attempt) {
        return filings.lock(feedbackId)
                .filter(filing -> filing.getState() == FilingState.DRAFTING && filing.getAttempt() == attempt)
                .orElse(null);
    }

    private static void count(ProductFeedbackFiling filing, long inputTokens, long outputTokens) {
        filing.setInputTokens(filing.getInputTokens() + inputTokens);
        filing.setOutputTokens(filing.getOutputTokens() + outputTokens);
    }

    private boolean overBudget(ProductFeedback report, ProductFeedbackDestination destination, Instant now) {
        Instant day = now.truncatedTo(ChronoUnit.DAYS);
        return filings.countDraftedSince(day) >= destination.getDailyDraftLimit()
                || filings.countDraftedByAccountSince(Objects.requireNonNull(report.getAccountId()), day)
                        >= ACCOUNT_DAILY_DRAFTS;
    }
}
