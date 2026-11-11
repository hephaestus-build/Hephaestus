package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long Hephaestus keeps what it says about a person: every conversation entry, every feedback and every practice
 * result. One window, so a person learns one number.
 *
 * @param feedbackRetention the age at which conversation entries, feedback and practice results are erased. An entry
 *     counts from when it was written, feedback from its delivery or its creation when never delivered, and a result
 *     from when it was recorded, once no kept feedback cites it.
 */
@ConfigurationProperties(prefix = "hephaestus")
public record FeedbackRetentionProperties(
        @DefaultValue("P400D") Duration feedbackRetention) {
    public FeedbackRetentionProperties {
        // The weekly conversation sweep erases two weeks early, so a shorter retention could not be kept.
        if (feedbackRetention.compareTo(Duration.ofDays(15)) < 0) {
            throw new IllegalArgumentException("hephaestus.feedback-retention must be at least 15 days");
        }
    }
}
