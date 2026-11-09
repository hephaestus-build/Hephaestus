package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties.Limit;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Shared attempt budgets reserve capacity for essential mail. */
@Component
@ConditionalOnServerRole
public class EmailRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(EmailRateLimiter.class);

    /** How long to wait when the budget store cannot say when it refills. */
    static final Duration UNKNOWN_REFILL = Duration.ofMinutes(1);

    private final BucketResolver buckets;
    private final EmailRateLimitProperties properties;

    public EmailRateLimiter(BucketResolver buckets, EmailRateLimitProperties properties) {
        this.buckets = buckets;
        this.properties = properties;
    }

    /**
     * Takes one attempt from the shared budgets.
     *
     * @return empty when the attempt may go ahead, otherwise how long until the budget refills
     */
    public Optional<Duration> acquire(boolean optional) {
        Optional<Duration> refused = optional ? consume("email:optional", properties.optional()) : Optional.empty();
        return refused.isPresent() ? refused : consume("email:total", properties.total());
    }

    private Optional<Duration> consume(String key, Limit limit) {
        var probe = buckets.tryConsume(
                key,
                limit.bucketConfiguration(),
                failure -> log.warn(
                        "email: capacity store unavailable cause={}",
                        failure.getClass().getName()));
        if (probe == null) return Optional.of(UNKNOWN_REFILL);
        if (probe.isConsumed()) return Optional.empty();
        return Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()).plusSeconds(1));
    }
}
