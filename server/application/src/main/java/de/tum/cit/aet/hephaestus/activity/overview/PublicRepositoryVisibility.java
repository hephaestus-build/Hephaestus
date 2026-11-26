package de.tum.cit.aet.hephaestus.activity.overview;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
class PublicRepositoryVisibility {
    private final double maxAgeSeconds;

    PublicRepositoryVisibility(
            @Value("${hephaestus.public-activity.repository-visibility-max-age:48h}") Duration maxAge) {
        Assert.isTrue(
                !maxAge.isNegative() && !maxAge.isZero(), "Public repository visibility max age must be positive");
        this.maxAgeSeconds = maxAge.getSeconds() + maxAge.getNano() / 1_000_000_000.0;
    }

    double maxAgeSeconds() {
        return maxAgeSeconds;
    }
}
