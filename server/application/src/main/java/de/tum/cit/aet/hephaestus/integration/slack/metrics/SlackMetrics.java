package de.tum.cit.aet.hephaestus.integration.slack.metrics;

public final class SlackMetrics {

    public static final String SLACK_API_RATELIMIT_THROTTLED_UNTIL_SECONDS =
            "slack.api.ratelimit.throttled_until_seconds";
    public static final String SLACK_API_RATELIMIT_THROTTLES = "slack.api.ratelimit.throttles";

    private SlackMetrics() {}
}
