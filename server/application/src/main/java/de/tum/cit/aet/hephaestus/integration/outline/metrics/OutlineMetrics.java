package de.tum.cit.aet.hephaestus.integration.outline.metrics;

public final class OutlineMetrics {

    public static final String OUTLINE_API_RATELIMIT_LIMIT = "outline.api.ratelimit.limit";
    public static final String OUTLINE_API_RATELIMIT_REMAINING = "outline.api.ratelimit.remaining";
    public static final String OUTLINE_API_RATELIMIT_SECONDS_UNTIL_RESET = "outline.api.ratelimit.seconds_until_reset";
    public static final String OUTLINE_API_RATELIMIT_THROTTLES = "outline.api.ratelimit.throttles";

    public static final String OUTLINE_WEBHOOK_SUBSCRIPTION_DELETION_FAILED =
            "outline.webhook.subscription.deletion.failed";
    public static final String OUTLINE_WEBHOOK_SUBSCRIPTION_UNROUTED = "outline.webhook.subscription.unrouted";

    private OutlineMetrics() {}
}
