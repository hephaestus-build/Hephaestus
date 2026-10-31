package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class FeedbackAccountErasureAdapter implements AccountErasureContributor {
    private final ProductFeedbackRepository feedback;

    @Override
    @Transactional
    public void eraseAccount(long accountId) {
        feedback.deleteAllByAccountId(accountId);
    }
}
