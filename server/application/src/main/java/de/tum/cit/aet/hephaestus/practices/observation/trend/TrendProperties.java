package de.tum.cit.aet.hephaestus.practices.observation.trend;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Research parameters for the opportunity-indexed trend estimator. */
@Getter
@Setter
@ConfigurationProperties(prefix = "hephaestus.practices.trend")
public class TrendProperties {

    private int bundleSize = 4;
    private int minBundleSize = 4;
    private double ropeHalfWidth = 0.15;
    private double credibilityThreshold = 0.90;

    @PostConstruct
    void validate() {
        if (bundleSize < 1 || minBundleSize < 1 || minBundleSize > bundleSize) {
            throw new IllegalArgumentException("Trend min-bundle-size must be between 1 and bundle-size");
        }
        if (!(ropeHalfWidth > 0.0 && ropeHalfWidth < 0.5)) {
            throw new IllegalArgumentException("Trend rope-half-width must be between 0 and 0.5");
        }
        if (!(credibilityThreshold > 0.5 && credibilityThreshold < 1.0)) {
            throw new IllegalArgumentException("Trend credibility-threshold must be between 0.5 and 1");
        }
    }
}
