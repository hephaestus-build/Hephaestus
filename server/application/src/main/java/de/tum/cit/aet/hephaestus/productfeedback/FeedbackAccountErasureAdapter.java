package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class FeedbackAccountErasureAdapter implements AccountErasureContributor {
    private final SurveyParticipationRepository participations;
    private final ProductFeedbackRepository feedback;
    private final SurveyEmailInvitationRepository emailInvitations;

    @Override
    @Transactional
    public void eraseAccount(long accountId) {
        participations.deleteAllByAccountId(accountId);
        feedback.deleteAllByAccountId(accountId);
        emailInvitations.deleteAllByAccountId(accountId);
        emailInvitations.eraseRequester(accountId);
    }
}
