package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * Capability set declared by per-kind {@link IntegrationManifest}. Practices declare
 * required capabilities; the UI gates on the workspace's union. Each declared
 * capability binds to a specific bean-wiring requirement enforced at startup by
 * {@code IntegrationFrameworkBootstrap}.
 *
 * <p>This enum is intentionally narrow: every value is backed by a shipped SPI.
 */
public enum Capability {
    /** Receives HTTP webhook events. */
    WEBHOOK_INGEST,
    /** OAuth refresh tokens are minted via {@code TokenRefresher}. */
    TOKEN_REFRESH,

    FEEDBACK_DELIVERY,
    INLINE_FEEDBACK,
}
