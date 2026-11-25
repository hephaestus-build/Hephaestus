package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The report state and quota behind a sandbox draft run. Routing validates and redacts the answer. */
@Service
@ConditionalOnServerRole
class DraftingService implements ProductFeedbackDrafts, ProductFeedbackDraftCalls {
    static final Duration HOLD = Duration.ofMinutes(5);
    /** How long a report waits for its draft before it routes without one. */
    static final Duration EXPIRY = Duration.ofMinutes(10);

    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackRepository feedback;
    private final DestinationSettings destinations;
    private final Clock clock;
    private final ProductFeedbackMeters meters;

    DraftingService(
            ProductFeedbackFilingRepository filings,
            ProductFeedbackRepository feedback,
            DestinationSettings destinations,
            Clock clock,
            ProductFeedbackMeters meters) {
        this.filings = filings;
        this.feedback = feedback;
        this.destinations = destinations;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DraftCall> waiting(UUID feedbackId, int generation) {
        ProductFeedbackFiling filing = filings.findById(feedbackId)
                .filter(row -> waits(row, generation))
                .orElse(null);
        ProductFeedback report = feedback.findById(feedbackId).orElse(null);
        ProductFeedbackDestination destination = destinations.current();
        Long model = destination.getLlmModelId();
        if (filing == null || report == null || model == null || !filing.isFileDisclosed()) return Optional.empty();
        String input = filing.getPromptInput();
        if (input == null) return Optional.empty();
        String user = DraftPrompt.user(
                report.getKind(),
                input,
                filing.getPageRoute(),
                report.getAppVersion(),
                Snapshot.read(destination.getSnapshot()));
        return Optional.of(new DraftCall(model, DraftPrompt.SYSTEM, user, DraftPrompt.MAX_OUTPUT_TOKENS));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean waits(UUID feedbackId, int generation) {
        return filings.findById(feedbackId)
                .filter(row -> waits(row, generation))
                .isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean admitCall(UUID feedbackId, int generation, long modelId) {
        var destination = destinations.locked();
        var filing =
                filings.lock(feedbackId).filter(row -> waits(row, generation)).orElse(null);
        var report = feedback.findById(feedbackId).orElse(null);
        if (filing == null
                || report == null
                || !filing.isFileDisclosed()
                || !Objects.equals(destination.getLlmModelId(), modelId)) return false;
        Instant now = clock.instant();
        Instant day = now.truncatedTo(ChronoUnit.DAYS);
        boolean reserved = filing.getDraftAdmittedAt() != null
                && !filing.getDraftAdmittedAt().isBefore(day);
        long instanceUsed = filings.countDraftedSince(day);
        long accountUsed = filings.countDraftedByAccountSince(Objects.requireNonNull(report.getAccountId()), day);
        if (destination.getDailyDraftLimit() == 0
                || (reserved
                        ? instanceUsed > destination.getDailyDraftLimit()
                        : instanceUsed >= destination.getDailyDraftLimit())
                || (reserved
                        ? accountUsed > FileIssueQueue.ACCOUNT_DAILY_DRAFTS
                        : accountUsed >= FileIssueQueue.ACCOUNT_DAILY_DRAFTS)) return false;
        filing.setDraftAdmittedAt(now);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Subject subject) {
        filings.lock(subject.feedbackId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimed(Subject subject) {
        return transition(subject, FilingLifecycle.Event.DRAFT_CLAIMED);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deferred(Subject subject) {
        transition(subject, FilingLifecycle.Event.DRAFT_DEFERRED);
    }

    private boolean transition(Subject subject, FilingLifecycle.Event event) {
        var filing = filings.lock(subject.feedbackId())
                .filter(row -> waits(row, subject.generation()))
                .orElse(null);
        if (filing == null) return false;
        filing.apply(event);
        filing.setUpdatedAt(clock.instant());
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForUsage(UUID feedbackId) {
        filings.lock(feedbackId);
    }

    @Override
    @Transactional
    public void count(UUID feedbackId, long inputTokens, long outputTokens) {
        filings.lock(feedbackId).ifPresent(filing -> {
            filing.setInputTokens(filing.getInputTokens() + inputTokens);
            filing.setOutputTokens(filing.getOutputTokens() + outputTokens);
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void ended(Subject subject, @Nullable String answer, String reason) {
        var filing = filings.lock(subject.feedbackId())
                .filter(row -> waits(row, subject.generation()))
                .orElse(null);
        if (filing == null) return;
        Instant now = clock.instant();
        try {
            meters.draft(answer == null ? reason : "answered");
            filing.setDraftedAt(answer == null ? null : now);
            answered(filing, answer, now);
        } catch (FilingLifecycle.IllegalTransition refused) {
            meters.failed(refused);
            throw refused;
        }
    }

    /** The run's deadline, after which the report routes without a draft. */
    static @Nullable Instant deadline(ProductFeedbackFiling filing) {
        Instant started = filing.getDraftingStartedAt();
        return started == null ? null : started.plus(EXPIRY);
    }

    private static boolean waits(ProductFeedbackFiling filing, int generation) {
        return (filing.getState() == FilingState.DRAFT_QUEUED
                        || filing.getState() == FilingState.DRAFTING
                        || filing.getState() == FilingState.DRAFT_WAITING)
                && filing.getAttempt() == generation;
    }

    /** Hands the report to routing, with the model's answer or without one. */
    static void answered(ProductFeedbackFiling filing, @Nullable String answer, Instant now) {
        filing.setAnswer(answer);
        filing.setDraftingStartedAt(null);
        filing.apply(FilingLifecycle.Event.DRAFT_ENDED);
        filing.setNextAttemptAt(now);
        filing.setUpdatedAt(now);
    }
}
