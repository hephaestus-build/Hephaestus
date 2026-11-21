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
        rows.deleteWorkspaceFeedback(ws);
        rows.deleteWorkspaceMembers(ws);
        rows.deleteWorkspaceResults(ws);
        rows.deleteWorkspaceReviews(ws);
        rows.deleteWorkspaceBatches(ws);
        rows.deleteWorkspaceSchedules(ws);
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
