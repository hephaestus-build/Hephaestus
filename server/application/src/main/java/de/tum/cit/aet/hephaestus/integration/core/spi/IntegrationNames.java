package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * The name a person knows an integration by, as its {@link IntegrationManifest} declares it. A port so a
 * module outside {@code integration} can name one in a sentence without reading the manifest registry.
 */
public interface IntegrationNames {
    /** The manifest's display name, or a generic word when no manifest for the kind is registered. */
    String displayName(IntegrationKind kind);
}
