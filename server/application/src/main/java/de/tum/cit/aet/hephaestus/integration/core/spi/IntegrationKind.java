package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * Identifies a specific external system Hephaestus integrates with.
 *
 * <p>Each value belongs to exactly one {@link IntegrationFamily}. The family
 * narrowing lets cross-cutting code dispatch by family while vendor-specific code
 * dispatches by kind.
 *
 * <p>This is the cross-module enum. Vendor-private code may use further narrowing
 * (e.g. {@code IdentityProviderType.from(kind)} for SCM-only paths) but the dependency
 * direction is one-way: the SPI does not know about vendor-private types.
 */
public enum IntegrationKind {
    /** GitHub.com or GitHub Enterprise Server (host disambiguates). */
    GITHUB(IntegrationFamily.SCM),
    /** GitLab.com or self-hosted GitLab (host disambiguates). */
    GITLAB(IntegrationFamily.SCM),
    /** Slack workspace. */
    SLACK(IntegrationFamily.MESSAGING),
    /** Outline knowledge base (getoutline.com cloud or a self-hosted host). */
    OUTLINE(IntegrationFamily.DOCUMENTATION),
    /** Read-only organizational eligibility; never a practice-review source. */
    KEYCLOAK_DIRECTORY(IntegrationFamily.DIRECTORY);

    private final IntegrationFamily family;

    IntegrationKind(IntegrationFamily family) {
        this.family = family;
    }

    public IntegrationFamily family() {
        return family;
    }
}
