package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackDelivery;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackReplies;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries out Hephaestus's Slack posts ({@link ActionKind#SLACK_POST}): the reply to a Slack turn through
 * {@link SlackMentorProjector}, and a weekly digest's note through {@link SlackWeeklyDigestPointer}. Each post carries
 * its entry in its metadata, so an attempt whose post may have reached Slack reads the history first. Without the Slack
 * integration, nothing can post.
 */
@Component
@ConditionalOnServerRole
class SlackPostAdapter implements ActionAdapter {

    private final ObjectProvider<SlackMentorProjector> projector;
    private final ObjectProvider<SlackWeeklyDigestPointer> digests;
    private final MentorSlackReplies replies;
    private final MentorSlackDelivery delivery;

    SlackPostAdapter(
            ObjectProvider<SlackMentorProjector> projector,
            ObjectProvider<SlackWeeklyDigestPointer> digests,
            MentorSlackReplies replies,
            MentorSlackDelivery delivery) {
        this.projector = projector;
        this.digests = digests;
        this.replies = replies;
        this.delivery = delivery;
    }

    @Override
    public ActionKind kind() {
        return ActionKind.SLACK_POST;
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        var payload = replies.payload(attempt);
        if (payload.turnId() != null) {
            SlackMentorProjector replies = projector.getIfAvailable();
            return replies == null
                    ? new ActionResult.Cancelled(ActionStopReason.NOT_CONFIGURED)
                    : replies.attempt(attempt, payload);
        }
        SlackWeeklyDigestPointer notes = digests.getIfAvailable();
        return notes == null
                ? new ActionResult.Cancelled(ActionStopReason.NOT_CONFIGURED)
                : notes.attempt(attempt, payload);
    }

    /** The entry keeps how its post ended, after the outbox deletes the ended action. */
    @Override
    public void ended(ActionEnded ended) {
        delivery.ended(
                Objects.requireNonNull(ended.workspaceId()),
                MentorSlackReplies.entryOf(ended.idempotencyKey()),
                switch (ended.state()) {
                    case DONE -> MentorEntry.SlackDelivery.DELIVERED;
                    case UNCERTAIN -> MentorEntry.SlackDelivery.UNCERTAIN;
                    default -> MentorEntry.SlackDelivery.FAILED;
                });
    }
}
