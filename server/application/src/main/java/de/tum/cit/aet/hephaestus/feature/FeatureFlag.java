package de.tum.cit.aet.hephaestus.feature;

/**
 * Canonical registry of the operational feature flags. Each one is a Spring Boot configuration property under
 * {@code hephaestus.features.flags.<key>}.
 * <p>
 * <strong>Adding a new flag:</strong>
 * <ol>
 *   <li>Add the enum constant here with its key</li>
 *   <li>Add a corresponding field to {@link FeatureFlagsDTO} and wire it in {@code from()}</li>
 *   <li>Workspace-offered features belong in {@code WorkspaceFeatures}, not here</li>
 *   <li>Add the property under {@code hephaestus.features.flags.<key>} in {@code application.yml}</li>
 *   <li>Run {@code vp run generate:api:client} to update the TypeScript client</li>
 * </ol>
 *
 * @see FeatureFlagService
 * @see FeatureFlagsDTO
 */
public enum FeatureFlag {
    PAGE_EXPERIENCE_METRICS("page-experience-metrics");

    private final String key;

    FeatureFlag(String key) {
        this.key = key;
    }

    /** The configuration property key. */
    public String key() {
        return key;
    }
}
