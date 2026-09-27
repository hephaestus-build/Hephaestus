package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResponseService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawal;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawalRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Only the practice page is covered: a note already posted on the work or raised in a conversation cannot be taken
 * back from here. Restoring puts the card back only through the ordinary read, with the evidence and consent checks
 * every card passes; nothing is sent again.
 *
 * <p>Both directions are idempotent. The feedback row lock serializes them with each other and with a recipient's
 * answer ({@link FeedbackResponseService}), so concurrent requests cannot open two withdrawals.
 */
@Service
@RequiredArgsConstructor
public class FeedbackWithdrawalService {

    private final FeedbackRepository feedbackRepository;
    private final FeedbackWithdrawalRepository withdrawalRepository;
    private final Clock clock;

    @Transactional
    public void setWithdrawn(long workspaceId, UUID feedbackId, long accountId, boolean withdrawn, String reason) {
        Feedback feedback = feedbackRepository
                .lockByIdAndWorkspaceId(feedbackId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback", feedbackId.toString()));
        if (feedback.getChannel() != FeedbackChannel.IN_APP) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only feedback on a developer's practice page can be withdrawn");
        }
        var active = withdrawalRepository.findActive(workspaceId, feedbackId);
        if (!withdrawn) {
            active.ifPresent(withdrawal -> withdrawal.restore(accountId, reason.strip(), clock.instant()));
            return;
        }
        if (active.isPresent()) {
            return;
        }
        if (feedback.getDeliveryState() != FeedbackDeliveryState.PREPARED
                && feedback.getDeliveryState() != FeedbackDeliveryState.DELIVERED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only feedback that is waiting on or shown on the practice page can be withdrawn");
        }
        withdrawalRepository.save(new FeedbackWithdrawal(feedback, accountId, reason.strip(), clock.instant()));
    }
}
