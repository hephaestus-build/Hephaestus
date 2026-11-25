package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import de.tum.cit.aet.hephaestus.integration.slack.metrics.SlackMetrics.MembershipStatus;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** Slack did not provide enough information to decide whether a sender is a full member. */
public final class SlackMembershipUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    SlackMembershipUnavailableException(MembershipStatus status, @Nullable Throwable cause) {
        super("Slack DM membership lookup failed: " + status.reason(), cause);
    }
}
