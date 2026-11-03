package de.tum.cit.aet.hephaestus.mentor;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long a conversation keeps its entries.
 *
 * @param conversationRetention the age after which every conversation entry is erased, as a delete erases it
 */
@ConfigurationProperties(prefix = "hephaestus.mentor")
public record MentorRetentionProperties(
        @DefaultValue("P400D") Duration conversationRetention) {
    public MentorRetentionProperties {
        // The weekly sweep erases two weeks early, so a shorter retention could not be kept.
        if (conversationRetention.compareTo(Duration.ofDays(15)) < 0) {
            throw new IllegalArgumentException("hephaestus.mentor.conversation-retention must be at least 15 days");
        }
    }
}
