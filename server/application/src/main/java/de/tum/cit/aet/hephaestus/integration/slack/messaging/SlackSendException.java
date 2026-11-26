package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import java.io.Serial;
import java.util.Set;

/** Raised by {@link SlackMessageService} when a Slack call fails or a delivery guard refuses it. */
public class SlackSendException extends RuntimeException {

    private static final Set<String> PROVIDER_UNAVAILABLE =
            Set.of("internal_error", "fatal_error", "service_unavailable", "request_timeout");
    private static final Set<String> UNKNOWN_OUTCOME = Set.of("transport_failure", "invalid_receipt", "unknown");

    static boolean isProviderUnavailable(String slackError) {
        return PROVIDER_UNAVAILABLE.contains(slackError);
    }

    public boolean isOutcomeUnknown() {
        return isProviderUnavailable(slackError) || UNKNOWN_OUTCOME.contains(slackError);
    }

    @Serial
    private static final long serialVersionUID = 1L;

    /** Sentinel for a failure without a rate-limit delay. */
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
     * The delay in milliseconds from a Slack 429 or an active recorded throttle, else {@link #NOT_RATE_LIMITED}.
     */
    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    /** Whether this failure carries a rate-limit delay. */
    public boolean isRateLimited() {
        return retryAfterMillis != NOT_RATE_LIMITED;
    }
}
