package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.practices.review.report.ScanReportService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one way that an integration's erasure removes the practice results derived from its work, and their feedback:
 * the same rows whether a disconnect, an uninstall or a person's erasure asks (ADR 0024). Callers select the results.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class DerivedResultEraser {
    private final PracticesPrivacyRepository rows;
    private final FeedbackCopyCleanupCustody custody;
    private final ScanReportService reports;

    /** Erases {@code ids} and the feedback based on them in the caller's transaction; returns the rows erased. */
    int erase(long ws, List<UUID> ids) {
        if (ids.isEmpty()) return 0;
        var selected = ids.toArray(UUID[]::new);
        custody.requireAcknowledged(ws, rows.feedbackForResults(ws, selected));
        reports.eraseResultReferences(ws, ids);
        int feedback = rows.deleteFeedbackForResults(ws, selected);
        return feedback + rows.deleteResults(ws, selected);
    }
}
