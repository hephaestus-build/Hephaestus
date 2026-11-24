package de.tum.cit.aet.hephaestus.core.settings.spi;

/**
 * What a release of the public usage page saw when it began (ADR 0153): whether the page was on, and the revision of
 * the settings row. The release stores its figures only if both are unchanged at its end.
 *
 * @param allowed whether the page was on
 * @param revision the version of the settings row, which every switch change, withdrawal and silent-mode change raises
 */
public record ReleaseGate(boolean allowed, long revision) {}
