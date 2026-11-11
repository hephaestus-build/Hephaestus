package de.tum.cit.aet.hephaestus.feature;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Unified service for evaluating feature flags.
 * <p>
 * Handles both {@link FeatureFlag.Kind#ROLE} flags (checked against the current
 * user's JWT authorities) and {@link FeatureFlag.Kind#CONFIG} flags (checked
 * against {@link FeatureProperties}).
 * <p>
 * This service is registered as a Spring bean and can be referenced in
 * {@code @PreAuthorize} SpEL expressions:
 * <pre>{@code
 * @PreAuthorize("@featureFlagService.isEnabled(T(de.tum.cit.aet.hephaestus.feature.FeatureFlag).ADMIN)")
 * }</pre>
 *
 * @see FeatureFlag
 * @see FeatureProperties
 */
@Service
public class FeatureFlagService {

    private final FeatureProperties featureProperties;

    public FeatureFlagService(FeatureProperties featureProperties) {
        this.featureProperties = featureProperties;
    }

    /**
     * Check if a feature flag is enabled for the current authenticated user.
     * <p>
     * For {@link FeatureFlag.Kind#ROLE} flags: checks the user's JWT authorities.
     * For {@link FeatureFlag.Kind#CONFIG} flags: checks the Spring Boot property.
     *
     * @param flag the feature flag to check
     * @return true if enabled
     */
    public boolean isEnabled(@Nullable FeatureFlag flag) {
        Objects.requireNonNull(flag, "flag must not be null");
        return switch (flag.kind()) {
            case ROLE -> hasAuthority(flag.key());
            case CONFIG -> featureProperties.isEnabled(flag.key());
        };
    }

    private boolean hasAuthority(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }
}
