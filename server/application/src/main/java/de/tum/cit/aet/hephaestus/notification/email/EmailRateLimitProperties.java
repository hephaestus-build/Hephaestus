package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties.Limit;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "hephaestus.email.rate-limit")
public record EmailRateLimitProperties(
        @DefaultValue("250") long dailyCapacity,
        @DefaultValue("200") long optionalDailyCapacity) {
    public EmailRateLimitProperties {
        if (dailyCapacity < 1 || optionalDailyCapacity < 1 || optionalDailyCapacity >= dailyCapacity) {
            throw new IllegalArgumentException(
                    "Email capacities must be positive, with optional capacity below total capacity");
        }
    }

    public Limit total() {
        return new Limit(dailyCapacity, Duration.ofDays(1));
    }

    public Limit optional() {
        return new Limit(optionalDailyCapacity, Duration.ofDays(1));
    }
}
