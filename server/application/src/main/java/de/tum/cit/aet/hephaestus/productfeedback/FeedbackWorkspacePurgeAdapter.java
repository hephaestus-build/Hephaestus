package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class FeedbackWorkspacePurgeAdapter implements WorkspacePurgeContributor {
    private final ProductFeedbackRepository feedback;

    @Override
    @Transactional
    public void deleteWorkspaceData(Long workspaceId) {
        feedback.deleteAllByWorkspaceId(workspaceId);
    }
}
