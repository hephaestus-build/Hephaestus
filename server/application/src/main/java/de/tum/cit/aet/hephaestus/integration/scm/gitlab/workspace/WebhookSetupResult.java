package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import org.jspecify.annotations.Nullable;

/**
 * Result of a GitLab webhook auto-registration attempt.
 *
 * <p>Used internally by {@link GitLabWebhookService} to communicate the outcome
 * to the activation service. Not exposed via REST API.
 *
 * @param status        the outcome status (SUCCESS, SKIPPED, or FAILED)
 * @param webhookId     the GitLab webhook ID (non-null when successful)
 * @param groupId       the GitLab group numeric ID (non-null when successful)
 * @param reason fixed diagnostic code, safe to log
 * @param httpStatus GitLab's response status, when a request failed
 */
public record WebhookSetupResult(
        Status status,
        @Nullable Long webhookId,
        @Nullable Long groupId,
        Reason reason,
        @Nullable Integer httpStatus) {
    public enum Status {
        SUCCESS,
        SKIPPED,
        FAILED,
    }

    public enum Reason {
        REGISTERED,
        NOT_GITLAB,
        ROUTING_SECRET_MISSING,
        CLIENT_UNAVAILABLE,
        GROUP_PATH_MISSING,
        CONNECTION_NOT_CONFIGURED,
        ROUTING_KEY_UNKNOWN,
        SUPERSEDED,
        PERMISSION_DENIED,
        API_ERROR,
    }

    /** Convenience: returns {@code true} when the webhook was successfully registered or already existed. */
    public boolean registered() {
        return status == Status.SUCCESS;
    }

    public static WebhookSetupResult success(long webhookId, long groupId) {
        return new WebhookSetupResult(Status.SUCCESS, webhookId, groupId, Reason.REGISTERED, null);
    }

    public static WebhookSetupResult failed(Reason reason, int httpStatus) {
        return new WebhookSetupResult(Status.FAILED, null, null, reason, httpStatus);
    }

    public static WebhookSetupResult skipped(Reason reason) {
        return new WebhookSetupResult(Status.SKIPPED, null, null, reason, null);
    }
}
