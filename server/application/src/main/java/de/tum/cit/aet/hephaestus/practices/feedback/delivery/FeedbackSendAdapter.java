package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendQueue;
import java.util.Objects;

/**
 * Sends one feedback to its work ({@link FeedbackSendAdapterConfiguration}). One service carries out every kind of send. Each
 * copy carries a marker, so an attempt whose write may have reached the provider reads the copy back first.
 */
public record FeedbackSendAdapter(ActionKind kind, FeedbackSendService sender, FeedbackSendTransactions transactions)
        implements ActionAdapter {

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        return sender.send(attempt);
    }

    @Override
    public void ended(ActionEnded ended) {
        transactions.ended(
                Objects.requireNonNull(ended.workspaceId()),
                FeedbackSendQueue.feedbackOf(ended.idempotencyKey()),
                ended.state());
    }
}
