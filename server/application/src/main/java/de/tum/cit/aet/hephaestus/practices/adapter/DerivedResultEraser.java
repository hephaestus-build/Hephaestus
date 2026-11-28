package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.advice.AdviceRedaction;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceStaleCause;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one way that an integration's erasure removes the practice results derived from its work, and their feedback:
 * the same rows whether a disconnect, an uninstall or a person's erasure asks (ADR 0024). Callers select the results.
 * The advice written from them loses its text in the same transaction (ADR 0160).
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class DerivedResultEraser {
    private final PracticesPrivacyRepository rows;
    private final FeedbackCopyCleanupCustody custody;
    private final ScanReportService reports;
    private final AdviceRedaction advice;

    /** Erases {@code ids} and the feedback based on them in the caller's transaction; returns the rows erased. */
    int erase(long ws, List<UUID> ids) {
        if (ids.isEmpty()) return 0;
        var selected = ids.toArray(UUID[]::new);
        custody.requireAcknowledged(ws, rows.feedbackForResults(ws, selected));
        reports.eraseResultReferences(ws, ids);
        advice.beforeResultsDeleted(ws, ids, AdviceStaleCause.WITHDRAWAL);
        int feedback = rows.deleteFeedbackForResults(ws, selected);
        return feedback + rows.deleteResults(ws, selected);
    }
}
