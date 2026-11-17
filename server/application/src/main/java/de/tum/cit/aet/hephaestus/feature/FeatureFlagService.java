package de.tum.cit.aet.hephaestus.feature;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Evaluates the operational feature flags against {@link FeatureProperties}.
 *
 * @see FeatureFlag
 */
@Service
public class FeatureFlagService {

    private final FeatureProperties featureProperties;

    public FeatureFlagService(FeatureProperties featureProperties) {
        this.featureProperties = featureProperties;
    }

    /** Whether the flag's configuration property is on. */
    public boolean isEnabled(@Nullable FeatureFlag flag) {
        Objects.requireNonNull(flag, "flag must not be null");
        return featureProperties.isEnabled(flag.key());
    }
}
