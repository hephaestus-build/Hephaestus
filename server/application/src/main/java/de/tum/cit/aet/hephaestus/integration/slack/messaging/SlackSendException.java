package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import java.io.Serial;

/** Raised by {@link SlackMessageService#sendForWorkspace}; carries the Slack error code. */
public class SlackSendException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Sentinel for {@link #retryAfterMillis}: this failure is not a rate-limit (HTTP 429). */
    public static final long NOT_RATE_LIMITED = -1L;

    private final long workspaceId;
    private final String channelId;
    private final String slackError;
    private final long retryAfterMillis;

    public SlackSendException(long workspaceId, String channelId, String slackError) {
        this(workspaceId, channelId, slackError, NOT_RATE_LIMITED);
    }

    public SlackSendException(long workspaceId, String channelId, String slackError, long retryAfterMillis) {
        super("slack send failed: workspaceId=" + workspaceId + " channelId=" + channelId + " error=" + slackError);
        this.workspaceId = workspaceId;
        this.channelId = channelId;
        this.slackError = slackError;
        this.retryAfterMillis = retryAfterMillis;
    }

    public SlackSendException(long workspaceId, String channelId, String slackError, Throwable cause) {
        this(workspaceId, channelId, slackError, NOT_RATE_LIMITED, cause);
    }

    public SlackSendException(
            long workspaceId, String channelId, String slackError, long retryAfterMillis, Throwable cause) {
        super(
                "slack send failed: workspaceId=" + workspaceId + " channelId=" + channelId + " error=" + slackError,
                cause);
        this.workspaceId = workspaceId;
        this.channelId = channelId;
        this.slackError = slackError;
        this.retryAfterMillis = retryAfterMillis;
    }

    public long workspaceId() {
        return workspaceId;
    }

    public String channelId() {
        return channelId;
    }

    public String slackError() {
        return slackError;
    }

    /**
     * The Slack {@code Retry-After} wait in milliseconds when this was a rate-limit (HTTP 429), else
     * {@link #NOT_RATE_LIMITED}. A caller uses it to back off exactly as long as Slack asked rather than
     * re-hammering on a fixed tick.
     */
    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    /** Whether this failure is a Slack rate-limit (HTTP 429) carrying a {@code Retry-After} wait. */
    public boolean isRateLimited() {
        return retryAfterMillis != NOT_RATE_LIMITED;
    }
}
