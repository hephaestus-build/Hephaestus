package de.tum.cit.aet.hephaestus.core.privacy.spi;

/**
 * A provider's own identifier of a resource, such as a Slack channel id, as a privacy fact names it. The type states
 * that the value is an identifier, never text.
 */
public record NativeId(String value) {}
