package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Drafting through the outbox. A report that waits for a draft has one {@code draft_report} action, which makes one
 * tool-less model call through the LLM proxy; the action's end hands the answer, or that none came, to routing. It
 * loads wherever the proxy does and decides nothing: the server's {@link RoutingService} validates the answer, redacts
 * it again and routes it.
 */
@Service
@ConditionalOnServerRole
class DraftingService implements ProductFeedbackDrafts, ProductFeedbackDraftCalls {
    /** A slow model must not decide where a report goes, so a failed call gets more chances before filing goes on. */
    static final int DRAFT_ATTEMPTS = 3;

    static final Duration HOLD = Duration.ofMinutes(5);
    /** How long a report waits for its draft before it routes without one. */
    static final Duration EXPIRY = Duration.ofMinutes(10);

    private static final String KEY_PREFIX = "draft:";

    private final ProductFeedbackFilingRepository filings;
    private final ProductFeedbackRepository feedback;
    private final DestinationSettings destinations;
    private final AgentActions actions;
    private final Clock clock;
    private final ProductFeedbackMeters meters;

    DraftingService(
            ProductFeedbackFilingRepository filings,
            ProductFeedbackRepository feedback,
            DestinationSettings destinations,
            AgentActions actions,
            Clock clock,
            ProductFeedbackMeters meters) {
        this.filings = filings;
        this.feedback = feedback;
        this.destinations = destinations;
        this.actions = actions;
        this.clock = clock;
        this.meters = meters;
    }

    /** The {@code draft_report} action of one generation, in the report's lane, so it runs before its filing. */
    static ActionRequest request(
            ObjectMapper mapper, UUID feedbackId, int generation, @Nullable Long accountId, Instant now) {
        return new ActionRequest(
                ActionKind.DRAFT_REPORT,
                null,
                FileIssueAdapter.lane(feedbackId),
                KEY_PREFIX + feedbackId + ":" + generation,
                mapper.valueToTree(new Payload(feedbackId, generation)),
                accountId,
                DRAFT_ATTEMPTS,
                now.plus(EXPIRY));
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
    @Transactional
    public boolean claimed(ActionAttempt attempt, UUID feedbackId, int generation) {
        return transition(attempt, feedbackId, generation, FilingLifecycle.Event.DRAFT_CLAIMED);
    }

    @Override
    @Transactional
    public boolean deferred(ActionAttempt attempt, UUID feedbackId, int generation) {
        return transition(attempt, feedbackId, generation, FilingLifecycle.Event.DRAFT_DEFERRED);
    }

    private boolean transition(ActionAttempt attempt, UUID id, int generation, FilingLifecycle.Event event) {
        if (!actions.holds(attempt)) return false;
        var filing = filings.lock(id).filter(row -> waits(row, generation)).orElse(null);
        if (filing == null) return false;
        filing.apply(event);
        filing.setUpdatedAt(clock.instant());
        return true;
    }

    @Override
    @Transactional
    public boolean answered(ActionAttempt attempt, UUID feedbackId, int generation, String answer) {
        if (!actions.holds(attempt)) return false;
        var filing =
                filings.lock(feedbackId).filter(row -> waits(row, generation)).orElse(null);
        if (filing == null) return false;
        filing.setAnswer(answer);
        filing.setDraftedAt(clock.instant());
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

    /** A done draft routes with the answer it kept; any other end routes without one. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void ended(ActionEnded ended) {
        // The key is draft:<report>:<generation>.
        String rest = ended.idempotencyKey().substring(KEY_PREFIX.length());
        UUID id = UUID.fromString(rest.substring(0, rest.indexOf(':')));
        int generation = Integer.parseInt(rest.substring(rest.indexOf(':') + 1));
        ProductFeedbackFiling filing =
                filings.lock(id).filter(row -> waits(row, generation)).orElse(null);
        if (filing == null) return;
        Instant now = clock.instant();
        try {
            if (ended.state() == ActionState.DONE && filing.getAnswer() != null) {
                meters.draft("answered");
                answered(filing, filing.getAnswer(), now);
                return;
            }
            meters.draft(ended.reason().name().toLowerCase(Locale.ROOT));
            filing.setDraftedAt(null);
            answered(filing, null, now);
        } catch (FilingLifecycle.IllegalTransition refused) {
            meters.failed(refused);
            throw refused;
        }
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
        filing.apply(FilingLifecycle.Event.DRAFT_ENDED);
        filing.setNextAttemptAt(now);
        filing.setUpdatedAt(now);
    }
}
