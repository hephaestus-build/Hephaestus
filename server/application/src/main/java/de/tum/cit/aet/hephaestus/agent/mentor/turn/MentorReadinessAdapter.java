package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.workspace.spi.MemberMentorReadiness;
import de.tum.cit.aet.hephaestus.workspace.spi.MentorRefusalReason;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Heph's admission rule for the workspace's onboarding state: the same check a question passes. */
@Component
@RequiredArgsConstructor
class MentorReadinessAdapter implements MemberMentorReadiness {

    private final MentorAdmission admission;

    @Override
    public Optional<MentorRefusalReason> refusal(long workspaceId, long developerId) {
        return admission.refusal(workspaceId, developerId).map(MentorRefusal::reason);
    }
}
