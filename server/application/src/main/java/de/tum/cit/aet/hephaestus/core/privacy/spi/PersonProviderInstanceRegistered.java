package de.tum.cit.aet.hephaestus.core.privacy.spi;

/** Synchronous transaction event: an exact provider namespace exists before its source can collect. */
public record PersonProviderInstanceRegistered(long providerId) {}
