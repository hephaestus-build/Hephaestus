package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class FeedbackWorkspacePurgeAdapter implements WorkspacePurgeContributor {
    private final SurveyRepository surveys;
    private final SurveyParticipationRepository participations;
    private final ProductFeedbackRepository feedback;
    private final SurveyEmailInvitationRepository emailInvitations;

    @Override
    @Transactional
    public void deleteWorkspaceData(Long workspaceId) {
        participations.deleteAllByWorkspaceId(workspaceId);
        emailInvitations.deleteAllByWorkspaceId(workspaceId);
        surveys.deleteAllByWorkspaceId(workspaceId);
        feedback.deleteAllByWorkspaceId(workspaceId);
    }
}
