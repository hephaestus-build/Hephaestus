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
        REGISTERED("Registered"),
        NOT_GITLAB("Not a GitLab workspace"),
        ROUTING_SECRET_MISSING("The webhook routing secret is not configured."),
        CLIENT_UNAVAILABLE(
                "The GitLab webhook client is not available because hephaestus.integration.gitlab.enabled is false."),
        GROUP_PATH_MISSING("GitLab group path is missing"),
        CONNECTION_NOT_CONFIGURED("GitLab instance is not configured on the connection"),
        ROUTING_KEY_UNKNOWN("Webhook is registered under a routing key this server does not know"),
        SUPERSEDED("Another registration recorded a different webhook"),
        PERMISSION_DENIED(
                "Insufficient permissions. You need the Owner role on the GitLab group with the Premium tier."),
        API_ERROR("GitLab API error");

        private final String description;

        Reason(String description) {
            this.description = description;
        }
    }

    /** The same fixed explanation for people, with no provider payload or account name. */
    public @Nullable String failureReason() {
        if (registered()) return null;
        return reason.description + (httpStatus == null ? "" : " (HTTP " + httpStatus + ")");
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
