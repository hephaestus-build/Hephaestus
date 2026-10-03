package de.tum.cit.aet.hephaestus.core.settings.spi;

/**
 * Read port for the instance-wide emergency silent mode, consulted before Hephaestus posts content
 * outward. Reads hit the singleton row uncached: a cache would keep sending just after an engage,
 * which is the one direction an emergency brake must never be stale in.
 *
 * <p>Suppresses outbound delivery only. Reviews still run and record their observations while engaged,
 * and held-back feedback is never posted retroactively on release.
 */
public interface SilentModeQuery {
    boolean isSilentModeEngaged();
}
