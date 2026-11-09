package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.bucket4j.Bucket;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class EmailRateLimiterTest extends BaseUnitTest {
    @Test
    void shouldShareCapacityAcrossSendersAndReserveEssentialAttempts() {
        var store = new ConcurrentHashMap<String, Bucket>();
        BucketResolver buckets = (key, config) -> store.computeIfAbsent(
                key,
                ignored -> Bucket.builder().addLimit(config.getBandwidths()[0]).build());
        var first = new EmailRateLimiter(buckets, new EmailRateLimitProperties(3, 2));
        var second = new EmailRateLimiter(buckets, new EmailRateLimitProperties(3, 2));
        assertThat(first.acquire(true)).isEmpty();
        assertThat(second.acquire(true)).isEmpty();
        assertThat(first.acquire(true))
                .as("waits until the optional budget refills")
                .hasValueSatisfying(wait -> assertThat(wait).isPositive());
        assertThat(second.acquire(false)).isEmpty();
        assertThat(first.acquire(false)).isPresent();
    }

    @Test
    void shouldWithholdWhenCapacityStoreIsUnavailable() {
        var limiter = new EmailRateLimiter(
                (key, config) -> {
                    throw new IllegalStateException("store offline");
                },
                new EmailRateLimitProperties(3, 2));
        assertThat(limiter.acquire(false)).contains(EmailRateLimiter.UNKNOWN_REFILL);
        assertThat(limiter.acquire(true)).contains(EmailRateLimiter.UNKNOWN_REFILL);
    }
}
