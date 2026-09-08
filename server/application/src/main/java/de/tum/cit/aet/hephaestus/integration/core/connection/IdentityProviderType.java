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
    GITHUB,
    GITLAB,
    OIDC,
    SLACK,
    OUTLINE;

    /**
     * Narrow an {@link IntegrationKind} to the SCM-only subset. The dependency direction
     * is connection → spi, never the reverse; this method lives here so the SPI stays
     * vendor-agnostic.
     *
     * @throws IllegalArgumentException if {@code kind} is not an SCM kind
     */
    public static IdentityProviderType from(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> GITHUB;
            case GITLAB -> GITLAB;
            case SLACK, OUTLINE ->
                throw new IllegalArgumentException(
                        "IntegrationKind " + kind + " is not an SCM kind and has no IdentityProviderType");
        };
    }
}
