package de.tum.cit.aet.hephaestus.feature;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO containing the evaluation of all feature flags for the current user.
 * <p>
 * Each field corresponds to a {@link FeatureFlag} enum constant. The field names
 * use SCREAMING_SNAKE_CASE to match the enum constant names exactly, providing a
 * direct cross-language mapping between Java and the generated TypeScript types.
 * <p>
 * The OpenAPI codegen pipeline strips the "DTO" suffix, producing a TypeScript
 * type {@code FeatureFlags} with explicit boolean properties. Frontend code can
 * then use {@code keyof FeatureFlags} as the feature flag name type for
 * compile-time safety.
 * <p>
 * <strong>Adding a new flag:</strong> Add the enum constant to {@link FeatureFlag},
 * then add the corresponding field here and wire it in {@code from}.
 *
 * @see FeatureFlag
 * @see FeatureFlagController
 */
@Schema(description = "Feature flags evaluated for the current user")
public record FeatureFlagsDTO(
        // ── Authorization flags (account_feature role flags) ──────────────────────
        @Schema(description = "User can receive notifications")
        boolean NOTIFICATION_ACCESS,

        @Schema(description = "User has admin privileges") boolean ADMIN,

        // Operational/development flags (Spring Boot config)
        @Schema(description = "GitLab workspace creation feature is enabled")
        boolean GITLAB_WORKSPACE_CREATION) {
    /**
     * Evaluate all feature flags against the given service and build the DTO.
     *
     * @param service the feature flag service (evaluates flags for the current user)
     * @return a fully populated DTO
     */
    public static FeatureFlagsDTO from(FeatureFlagService service) {
        return new FeatureFlagsDTO(
                service.isEnabled(FeatureFlag.NOTIFICATION_ACCESS),
                service.isEnabled(FeatureFlag.ADMIN),
                service.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION));
    }
}
