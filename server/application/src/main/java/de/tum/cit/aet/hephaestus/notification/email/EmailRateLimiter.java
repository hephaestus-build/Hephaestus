package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitProperties.Limit;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Shared attempt budgets leave capacity for essential mail even during a survey campaign. */
@Component
@ConditionalOnServerRole
public class EmailRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(EmailRateLimiter.class);
    private final BucketResolver buckets;
    private final EmailRateLimitProperties properties;

    public EmailRateLimiter(BucketResolver buckets, EmailRateLimitProperties properties) {
        this.buckets = buckets;
        this.properties = properties;
    }

    public boolean acquire(boolean optional) {
        return (!optional || consume("email:optional", properties.optional()))
                && consume("email:total", properties.total());
    }

    private boolean consume(String key, Limit limit) {
        var probe = buckets.tryConsume(
                key,
                limit.bucketConfiguration(),
                failure -> log.warn(
                        "email: capacity store unavailable cause={}",
                        failure.getClass().getName()));
        return probe != null && probe.isConsumed();
    }
}
