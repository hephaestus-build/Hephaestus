package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;

/**
 * High-level identity provider type.
 *
 * <p>Used to distinguish provider-specific behavior (API clients, sync engines, UI icons)
 * without coupling to the specific authentication mechanism. {@link #GITHUB}/{@link #GITLAB}
 * are SCM providers; {@link #SLACK} is a messaging identity provider (federated login +
 * DM mentor) and {@link #OUTLINE} is a documentation identity provider (link-only OAuth,
 * document authorship attribution) — neither has an SCM sync surface.
 */
public enum IdentityProviderType {
    GITHUB(IntegrationKind.GITHUB, true),
    GITLAB(IntegrationKind.GITLAB, true),
    SLACK(IntegrationKind.SLACK, false),
    OUTLINE(IntegrationKind.OUTLINE, false);

    private final IntegrationKind kind;
    private final boolean scm;

    IdentityProviderType(IntegrationKind kind, boolean scm) {
        this.kind = kind;
        this.scm = scm;
    }

    /**
     * Narrow an {@link IntegrationKind} to the SCM-only subset. The dependency direction
     * is connection → spi, never the reverse; this method lives here so the SPI stays
     * vendor-agnostic.
     *
     * @throws IllegalArgumentException if {@code kind} is not an SCM kind
     */
    public static IdentityProviderType from(IntegrationKind kind) {
        for (IdentityProviderType type : values()) {
            if (type.kind == kind && type.scm) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "IntegrationKind " + kind + " is not an SCM kind and has no IdentityProviderType");
    }

    /** The integration this provider is served by; the SPI vocabulary agent code dispatches on. */
    public IntegrationKind kind() {
        return kind;
    }
}
