package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonFeedbackAnswerRedaction;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.advice.AdviceRedaction;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceStaleCause;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one way that an integration's erasure removes the practice results derived from its work, and their feedback:
 * the same rows whether a disconnect, an uninstall or a person's erasure asks (ADR 0024). Callers select the results.
 * The advice written from them loses its text in the same transaction (ADR 0160). Unless the results reach their
 * retention, so do the Hephaestus answers that showed the feedback in a conversation, with what derives from them.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class DerivedResultEraser {
    private final PracticesPrivacyRepository rows;
    private final FeedbackCopyCleanupCustody custody;
    private final ScanReportService reports;
    private final AdviceRedaction advice;
    private final PersonFeedbackAnswerRedaction answers;

    /**
     * Erases {@code ids} and the feedback based on them in the caller's transaction; returns the rows erased. The cause
     * tells the conversation side whether the answers that read advice from them go too.
     */
    int erase(long ws, List<UUID> ids, AdviceStaleCause cause) {
        if (ids.isEmpty()) return 0;
        var selected = ids.toArray(UUID[]::new);
        var feedback = rows.feedbackForResults(ws, selected);
        custody.requireAcknowledged(ws, feedback);
        reports.eraseResultReferences(ws, ids);
        advice.beforeResultsDeleted(ws, ids, cause);
        if (cause != AdviceStaleCause.RETENTION && !feedback.isEmpty()) redactChatAnswers(ws, feedback);
        int erased = rows.deleteFeedbackForResults(ws, selected);
        return erased + rows.deleteResults(ws, selected);
    }

    /** After the results and the advice are locked, as the person erasure orders them, and before the copies go. */
    private void redactChatAnswers(long ws, List<UUID> feedback) {
        var turns = new LinkedHashSet<UUID>();
        var entries = new LinkedHashSet<UUID>();
        for (var copy : rows.chatCopiesOf(ws, feedback.toArray(UUID[]::new))) {
            UUID turn = copy.getTurn();
            UUID entry = copy.getEntry();
            if (turn != null) turns.add(turn);
            if (entry != null) entries.add(entry);
        }
        if (!turns.isEmpty() || !entries.isEmpty()) answers.redactClaimedAnswers(Map.of(ws, turns), entries);
    }
}
