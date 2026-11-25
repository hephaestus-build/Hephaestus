package de.tum.cit.aet.hephaestus.integration.slack.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class SlackMetrics {

    public static final String SLACK_API_RATELIMIT_THROTTLED_UNTIL_SECONDS =
            "slack.api.ratelimit.throttled_until_seconds";
    public static final String SLACK_API_RATELIMIT_THROTTLES = "slack.api.ratelimit.throttles";
    public static final String SLACK_DM_MEMBERSHIP_LOOKUPS = "slack.dm.membership.lookups";

    public enum MembershipStatus {
        CONFIRMED("confirmed", "none"),
        INELIGIBLE("ineligible", "none"),
        CREDENTIAL("unconfirmed", "credential"),
        PROVIDER("unconfirmed", "provider"),
        RATE_LIMITED("unconfirmed", "rate_limited"),
        TRANSPORT("unconfirmed", "transport"),
        PAYLOAD("unconfirmed", "payload");

        private final String outcome;
        private final String reason;

        MembershipStatus(String outcome, String reason) {
            this.outcome = outcome;
            this.reason = reason;
        }

        public boolean unconfirmed() {
            return "unconfirmed".equals(outcome);
        }

        public String reason() {
            return reason;
        }
    }

    private final MeterRegistry registry;

    public SlackMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (var status : MembershipStatus.values()) {
            membershipLookups(status);
        }
    }

    public void recordMembershipLookup(MembershipStatus status) {
        membershipLookups(status).increment();
    }

    private Counter membershipLookups(MembershipStatus status) {
        return registry.counter(
                SlackMetrics.SLACK_DM_MEMBERSHIP_LOOKUPS, "outcome", status.outcome, "reason", status.reason);
    }
}
