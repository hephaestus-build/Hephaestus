package de.tum.cit.aet.hephaestus.feature;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for operational feature flags.
 * <p>
 * Maps to {@code hephaestus.features.flags} in application.yml:
 * <pre>{@code
 * hephaestus:
 *   features:
 *     flags:
 *       page-experience-metrics: false
 * }</pre>
 *
 * @param flags map of feature flag keys to their enabled state
 * @see FeatureFlag
 * @see FeatureFlagService
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.features")
public record FeatureProperties(@DefaultValue Map<String, Boolean> flags) {
    /**
     * Check if a CONFIG flag is enabled. Returns {@code false} for unknown keys.
     *
     * @param key the config flag key (e.g. "page-experience-metrics")
     * @return true if the flag is explicitly set to true
     */
    public boolean isEnabled(String key) {
        return Boolean.TRUE.equals(flags.get(key));
    }
}
