package de.tum.cit.aet.hephaestus.feature;

/**
 * Canonical registry of all feature flags in the system.
 * <p>
 * This enum is the single source of truth for feature flag names.
 * Each flag is tagged with its {@link Kind} to indicate whether it maps
 * to an {@code account_feature} role flag or a Spring Boot configuration property.
 * <p>
 * <strong>Adding a new flag:</strong>
 * <ol>
 *   <li>Add the enum constant here with the correct kind and key</li>
 *   <li>Add a corresponding field to {@link FeatureFlagsDTO} and wire it in {@code from()}</li>
 *   <li>For {@code ROLE} flags: nothing in the product grants one. An {@code account_feature} row
 *       is written only by a database edit and follows the account into every workspace, so a
 *       feature a workspace offers its members belongs in {@code WorkspaceFeatures} instead, which
 *       workspace admins change through an audited request</li>
 *   <li>For {@code CONFIG} flags: add the property under
 *       {@code hephaestus.features.flags.<key>} in {@code application.yml}</li>
 *   <li>Run {@code vp run generate:api:client} to update the TypeScript client</li>
 * </ol>
 *
 * @see FeatureFlagService
 * @see FeatureFlagsDTO
 */
public enum FeatureFlag {
    // ── Authorization flags (account_feature role flags) ──────────────────────
    NOTIFICATION_ACCESS(Kind.ROLE, "notification_access"),
    // Reports instance-admin status: the authority key is the namespaced `app_admin` the issuer mints
    // for Account.AppRole APP_ADMIN. NOT a grantable escalation — JwtPrincipalFactory strips
    // `app_admin`/`admin` from account_feature rows, so this reflects appRole only.
    ADMIN(Kind.ROLE, "app_admin"),

    // Operational/development flags (Spring Boot config)
    GITLAB_WORKSPACE_CREATION(Kind.CONFIG, "gitlab-workspace-creation");

    private final Kind kind;
    private final String key;

    FeatureFlag(Kind kind, String key) {
        this.kind = kind;
        this.key = key;
    }

    /**
     * The lookup key: role name for {@link Kind#ROLE} flags,
     * config property key for {@link Kind#CONFIG} flags.
     */
    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * Indicates whether a flag is backed by an {@code account_feature} role flag
     * or a Spring Boot configuration property.
     */
    public enum Kind {
        /** Flag backed by an {@code account_feature} role flag, surfaced on the user's JWT. */
        ROLE,
        /** Flag backed by a Spring Boot configuration property. */
        CONFIG,
    }
}
