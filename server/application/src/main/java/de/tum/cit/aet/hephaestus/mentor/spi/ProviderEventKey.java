package de.tum.cit.aet.hephaestus.mentor.spi;

/** The idempotency key of a provider event: its verified binding and the provider's own event id. */
public record ProviderEventKey(long bindingId, String nativeEventId) {}
