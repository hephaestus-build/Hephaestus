package de.tum.cit.aet.hephaestus.integration.slack.channel;

import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import java.util.Arrays;

/** The administrator's requested outcome; delivery owns ACTIVATING and FAILED. */
public enum SlackChannelConsentTarget {
    ACTIVE,
    PAUSED,
    REVOKED;

    public ConsentState state() {
        return ConsentState.valueOf(name());
    }

    public static boolean supports(ConsentState state) {
        return Arrays.stream(values()).anyMatch(target -> target.state() == state);
    }
}
