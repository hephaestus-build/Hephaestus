package de.tum.cit.aet.hephaestus.mentor.spi;

import org.jspecify.annotations.Nullable;

/** The provider actor that the transport verified, and the account it is linked to, if any. */
public record VerifiedActor(String nativeActorId, @Nullable Long linkedAccountId) {}
