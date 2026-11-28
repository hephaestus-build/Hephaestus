package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyCleanupCustody;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticesWorkspacePurgeAdapter implements WorkspacePurgeContributor {
    private final PracticesPrivacyRepository rows;
    private final FeedbackCopyCleanupCustody custody;

    @Override
    public void deleteWorkspaceData(Long ws) {
        custody.requireAcknowledged(ws, rows.workspaceFeedbackIds(ws));
        // Advice before the results it was written from (ADR 0160), and result locks before revision locks, as every
        // redaction takes them: lock the results in id order, then delete the advice, then the results.
        rows.lockWorkspaceResults(ws);
        rows.deleteWorkspaceAdvice(ws);
        rows.deleteWorkspaceFeedback(ws);
        rows.deleteWorkspaceMembers(ws);
        rows.deleteWorkspaceResults(ws);
        rows.deleteWorkspaceReviews(ws);
        rows.deleteWorkspaceBatches(ws);
        rows.deleteWorkspaceSchedules(ws);
        rows.deleteWorkspaceSetups(ws);
        rows.deferConstraints();
        rows.deleteWorkspaceVersions(ws);
        rows.deleteWorkspacePractices(ws);
        rows.deleteWorkspaceGroups(ws);
        rows.deleteWorkspacePracticeRepository(ws);
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
