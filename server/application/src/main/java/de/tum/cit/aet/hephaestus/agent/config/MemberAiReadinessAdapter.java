package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.workspace.spi.AiRefusalReason;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiReadiness;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admission rules for one member: Heph's, which each question passes, and review admission's for a review whose
 * only subject is the member ({@code ReviewMemberAiPolicy}).
 */
@Component
@RequiredArgsConstructor
class MemberAiReadinessAdapter implements MemberAiReadiness {

    private final MentorAdmission admission;
    private final PersonProcessingSuppression suppression;
    private final MemberAiPreferences preferences;
    private final MemberAiRoutingAdapter routing;

    @Override
    public Optional<AiRefusalReason> conversationRefusal(long workspaceId, long accountId) {
        return admission.refusal(workspaceId, accountId).map(MentorRefusal::reason);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiRefusalReason> practiceReviewRefusal(long workspaceId, long accountId) {
        if (suppression.isAccountSuppressed(accountId)) return Optional.of(AiRefusalReason.PERSON_ERASED);
        var decision = preferences.forDeveloper(workspaceId, accountId);
        if (decision.choice() == AiChoice.NO_AI) return Optional.of(AiRefusalReason.NO_AI);
        if (!decision.permitsAi()) return Optional.of(AiRefusalReason.CHOICE_REQUIRED);
        if (routing.bindingForPeople(workspaceId, AgentPurpose.PRACTICE_REVIEW, List.<@Nullable Long>of(accountId))
                .isEmpty()) return Optional.of(AiRefusalReason.UNAVAILABLE);
        return Optional.empty();
    }
}
