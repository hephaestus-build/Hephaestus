package de.tum.cit.aet.hephaestus.activity.overview;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
class PublicRepositoryVisibility {
    private final Clock clock;
    private final Duration maxAge;

    PublicRepositoryVisibility(
            Clock clock, @Value("${hephaestus.public-activity.repository-visibility-max-age:48h}") Duration maxAge) {
        Assert.isTrue(
                !maxAge.isNegative() && !maxAge.isZero(), "Public repository visibility max age must be positive");
        this.clock = clock;
        this.maxAge = maxAge;
    }

    Instant confirmedAfter() {
        return clock.instant().minus(maxAge);
    }
}
